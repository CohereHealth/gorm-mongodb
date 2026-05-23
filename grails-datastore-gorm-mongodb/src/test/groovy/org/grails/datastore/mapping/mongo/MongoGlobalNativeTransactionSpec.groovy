package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec

class MongoGlobalNativeTransactionSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Order]
    }

    @Override
    Map getConfiguration() {
        [
            'grails.mongodb.nativeTransactions': true
        ]
    }

    def "test withNativeTransaction commits with global config enabled"() {
        when: "using withNativeTransaction with global native transactions enabled"
        boolean inNative = false
        Order.withNativeTransaction {
            new Order(number: "ORD-001").save(flush: true)
            inNative = Order.isInNativeTransaction()
        }

        then:
        inNative == true
        Order.count() == 1
    }

    def "test withNativeTransaction rollback with global config enabled"() {
        when: "exception causes rollback"
        Order.withNativeTransaction {
            new Order(number: "ORD-002").save(flush: true)
            throw new RuntimeException("Force rollback")
        }

        then:
        thrown(RuntimeException)
        Order.count() == 0
    }

    def "test isInNativeTransaction is false outside transaction"() {
        expect:
        !Order.isInNativeTransaction()
    }
}

@Entity
class Order {
    String number
}
