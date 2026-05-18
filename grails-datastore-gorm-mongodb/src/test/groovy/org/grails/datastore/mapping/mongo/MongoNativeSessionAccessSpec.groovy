package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.springframework.stereotype.Service

class MongoNativeSessionAccessSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Customer]
    }

    def "test access to native session from services and domains"() {
        given:
        def customerService = new CustomerService()

        when: "outside native transaction"
        def hasSession = Customer.isInNativeTransaction()
        def session = MongoNativeTransactionContext.getNativeSession()

        then:
        !hasSession
        session == null

        when: "inside native transaction"
        def result = Customer.withNativeTransaction { nativeSession ->
            def inTransaction = Customer.isInNativeTransaction()
            def currentSession = MongoNativeTransactionContext.getNativeSession()
            def serviceResult = customerService.processCustomer("John")

            return [
                inTransaction: inTransaction,
                currentSession: currentSession,
                serviceResult: serviceResult,
                sameSession: currentSession == nativeSession
            ]
        }

        then:
        result.inTransaction == true
        result.currentSession != null
        result.sameSession == true
        result.serviceResult.sessionAvailable == true
        result.serviceResult.inTransaction == true
    }
}

@Entity
class Customer {
    String name
}

@Service
class CustomerService {

    def processCustomer(String name) {
        // Access native session from service
        def session = MongoNativeTransactionContext.getNativeSession()
        def inTransaction = MongoNativeTransactionContext.isInNativeTransaction()

        new Customer(name: name).save(flush: true)

        return [
            sessionAvailable: session != null,
            inTransaction: inTransaction
        ]
    }
}