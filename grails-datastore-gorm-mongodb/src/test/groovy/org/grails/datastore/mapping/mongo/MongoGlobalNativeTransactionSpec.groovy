package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.springframework.transaction.annotation.Transactional

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

    @Transactional
    def "test global native transactions"() {
        when: "using regular GORM methods with global native transactions enabled"
        new Order(number: "ORD-001").save(flush: true)
        def inNative = Order.isInNativeTransaction()
        
        then:
        inNative == true
        Order.count() == 1
        
        when: "exception causes rollback"
        throw new RuntimeException("Force rollback")
        
        then:
        thrown(RuntimeException)
        Order.count() == 0 // Should be rolled back
    }
    
    def "test withNativeTransaction still works with global config"() {
        when: "using withNativeTransaction with global native transactions"
        def result = Order.withNativeTransaction { session ->
            new Order(number: "ORD-002").save(flush: true)
            return Order.isInNativeTransaction()
        }
        
        then:
        result == true
        Order.count() == 1
    }
}

@Entity
class Order {
    String number
}