package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class MongoSpringBootTransactionSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Employee]
    }
    
    @Override
    Map getConfiguration() {
        [
            'grails.mongodb.nativeTransactions': true
        ]
    }

    def "test Spring @Transactional with native transactions"() {
        given:
        def employeeService = new EmployeeService()
        
        when: "using @Transactional service method"
        def result = employeeService.createEmployeeWithDepartment("John", "Engineering")
        
        then:
        result.name == "John"
        result.department == "Engineering"
        Employee.count() == 1
        
        when: "exception in @Transactional method"
        try {
            employeeService.createEmployeeWithError("Jane", "Sales")
        } catch (RuntimeException e) {
            // Expected
        }
        
        then:
        Employee.count() == 1 // Should still be 1 due to rollback
    }
    
    def "test mixed Spring and GORM transactions"() {
        given:
        def employeeService = new EmployeeService()
        
        when: "GORM transaction calling Spring service"
        def result = Employee.withNativeTransaction { session ->
            def emp = employeeService.createEmployee("Bob")
            emp.department = "Marketing"
            emp.save(flush: true)
            return emp
        }
        
        then:
        result.department == "Marketing"
        Employee.count() == 2
    }
}

@Entity
class Employee {
    String name
    String department
}

@Service
class EmployeeService {
    
    @Transactional
    Employee createEmployeeWithDepartment(String name, String department) {
        def employee = new Employee(name: name, department: department)
        employee.save(flush: true)
        return employee
    }
    
    @Transactional
    Employee createEmployeeWithError(String name, String department) {
        def employee = new Employee(name: name, department: department)
        employee.save(flush: true)
        throw new RuntimeException("Simulated error")
    }
    
    Employee createEmployee(String name) {
        return new Employee(name: name).save(flush: true)
    }
}