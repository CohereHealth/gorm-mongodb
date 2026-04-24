package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

class MongoSpringBootTransactionSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Staff]
    }
    
    @Override
    Map getConfiguration() {
        [
            'grails.mongodb.nativeTransactions': true
        ]
    }

    def "test Spring @Transactional with native transactions"() {
        given:
        def staffService = new StaffService()
        
        when: "using @Transactional service method"
        def result = staffService.createStaffWithRole("John", "Engineering")
        
        then:
        result.name == "John"
        result.role == "Engineering"
        Staff.count() == 1
        
        when: "exception in @Transactional method"
        try {
            staffService.createStaffWithError("Jane", "Sales")
        } catch (RuntimeException e) {
            // Expected
        }
        
        then:
        Staff.count() == 1 // Should still be 1 due to rollback
    }
    
    def "test mixed Spring and GORM transactions"() {
        given:
        def staffService = new StaffService()
        
        when: "GORM transaction calling Spring service"
        def result = Staff.withNativeTransaction { session ->
            def member = staffService.createStaff("Bob")
            member.role = "Marketing"
            member.save(flush: true)
            return member
        }
        
        then:
        result.role == "Marketing"
        Staff.count() == 2
    }
}

@Entity
class Staff {
    String name
    String role
}

@Service
class StaffService {

    @Transactional
    Staff createStaffWithRole(String name, String role) {
        new Staff(name: name, role: role).save(flush: true)
    }

    @Transactional
    Staff createStaffWithError(String name, String role) {
        new Staff(name: name, role: role).save(flush: true)
        throw new RuntimeException("Simulated error")
    }

    Staff createStaff(String name) {
        new Staff(name: name).save(flush: true)
    }
}