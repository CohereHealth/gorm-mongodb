package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec

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

    def "test native transaction commit"() {
        when: "saving within a native transaction"
        Staff.withNativeTransaction {
            new Staff(name: "John", role: "Engineering").save(flush: true)
        }

        then:
        Staff.count() == 1
        Staff.findByName("John").role == "Engineering"
    }

    def "test native transaction rollback on exception"() {
        given:
        Staff.withNativeTransaction {
            new Staff(name: "Initial", role: "Setup").save(flush: true)
        }
        assert Staff.count() == 1

        when: "exception occurs in native transaction"
        Staff.withNativeTransaction {
            new Staff(name: "Jane", role: "Sales").save(flush: true)
            throw new RuntimeException("Simulated error")
        }

        then:
        thrown(RuntimeException)
        Staff.count() == 1 // Should still be 1 due to rollback
    }
}

@Entity
class Staff {
    String name
    String role
}
