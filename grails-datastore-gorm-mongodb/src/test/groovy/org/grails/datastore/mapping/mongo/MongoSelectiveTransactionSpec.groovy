package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import spock.lang.Specification

class MongoSelectiveTransactionSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [TestUser]
    }

    def "test selective native transaction usage"() {
        when: "using withNativeTransaction on static API"
        def result = TestUser.withNativeTransaction { session ->
            new TestUser(name: "John").save(flush: true)
            return TestUser.count()
        }
        
        then:
        result == 1
        TestUser.count() == 1
        
        when: "using withNativeTransaction on instance"
        def user = new TestUser(name: "Jane")
        user.withNativeTransaction { session ->
            user.save(flush: true)
            return session // Access to ClientSession
        }
        
        then:
        TestUser.count() == 2
        
        when: "exception in native transaction"
        TestUser.withNativeTransaction { session ->
            new TestUser(name: "Bob").save(flush: true)
            throw new RuntimeException("Force rollback")
        }
        
        then:
        thrown(RuntimeException)
        TestUser.count() == 2 // Should still be 2 due to rollback
    }
}

@Entity
class TestUser {
    String name
}