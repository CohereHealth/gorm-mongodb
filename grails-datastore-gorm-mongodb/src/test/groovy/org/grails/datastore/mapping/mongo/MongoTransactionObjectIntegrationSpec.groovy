package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Comprehensive integration test for native MongoDB transactions
 */
class MongoTransactionObjectIntegrationSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Company, Department, TxEmployee]
    }
    
    @Override
    Map getConfiguration() {
        [
            'grails.mongodb.nativeTransactions': true,
            'logging.level.org.grails.datastore.mapping.mongo': 'DEBUG'
        ]
    }

    def "test complete business transaction with rollback"() {
        given:
        def companyService = new CompanyService()
        
        when: "successful transaction"
        def result = companyService.createCompanyWithDepartments("TechCorp", ["Engineering", "Sales"])
        
        then:
        result.name == "TechCorp"
        Company.count() == 1
        Department.count() == 2
        Employee.count() == 0
        
        when: "transaction with rollback"
        try {
            companyService.createCompanyWithError("FailCorp", ["IT", "HR"])
        } catch (RuntimeException e) {
            // Expected
        }
        
        then: "all operations rolled back"
        Company.count() == 1 // Still only TechCorp
        Department.count() == 2 // Still only TechCorp departments
    }
    
    def "test nested service calls with inheritance"() {
        given:
        def companyService = new CompanyService()
        def employeeService = new TxEmployeeService()
        
        when: "nested native transaction calls"
        def result = Company.withNativeTransaction { session ->
            def company = companyService.createCompany("NestedCorp")
            def dept = companyService.createDepartment(company, "Development")
            employeeService.createEmployee(dept, "John Doe", 75000)
            
            return [
                company: company,
                inNative: Company.isInNativeTransaction(),
                sessionId: session.getServerSession().getIdentifier()
            ]
        }
        
        then:
        result.inNative == true
        result.company.name == "NestedCorp"
        Company.count() == 2
        Department.count() == 3
        Employee.count() == 1
    }
    
    def "test mixed transaction types with inheritance"() {
        when: "regular transaction with nested native call"
        def result = Company.withTransaction { status ->
            def company = new Company(name: "MixedCorp").save(flush: true)
            
            // This should inherit regular transaction behavior
            Company.withNativeTransaction { session ->
                new Department(name: "Mixed Dept", company: company).save(flush: true)
                return Company.isInNativeTransaction()
            }
        }
        
        then:
        result == false // Should inherit regular transaction
        Company.count() == 3
        Department.count() == 4
    }
}

@Entity
class Company {
    String name
    static hasMany = [departments: Department]
}

@Entity  
class Department {
    String name
    Company company
    static hasMany = [employees: TxEmployee]
    static belongsTo = [Company]
}

@Entity
class TxEmployee {
    String name
    Department department
    BigDecimal salary
    static belongsTo = [Department]
}

@Service
class CompanyService {
    
    @Transactional
    Company createCompanyWithDepartments(String companyName, List<String> deptNames) {
        def company = createCompany(companyName)
        deptNames.each { deptName ->
            createDepartment(company, deptName)
        }
        return company
    }
    
    @Transactional
    Company createCompanyWithError(String companyName, List<String> deptNames) {
        def company = createCompany(companyName)
        deptNames.each { deptName ->
            createDepartment(company, deptName)
        }
        throw new RuntimeException("Simulated business error")
    }
    
    Company createCompany(String name) {
        return new Company(name: name).save(flush: true)
    }
    
    Department createDepartment(Company company, String name) {
        return new Department(name: name, company: company).save(flush: true)
    }
}

@Service
class TxEmployeeService {
    
    TxEmployee createEmployee(Department department, String name, BigDecimal salary) {
        return new TxEmployee(name: name, department: department, salary: salary).save(flush: true)
    }
}