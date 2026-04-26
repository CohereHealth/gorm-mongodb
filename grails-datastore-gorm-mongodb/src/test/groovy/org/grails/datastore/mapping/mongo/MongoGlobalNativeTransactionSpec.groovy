package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.grails.datastore.mapping.mongo.config.MongoSettings
import org.springframework.transaction.annotation.Transactional

/**
 * Tests to verify that when global native transactions are enabled via config:
 * <pre>
 * grails.mongodb.nativeTransactionsEnabled: true
 * </pre>
 *
 * All three transaction mechanisms automatically use native MongoDB transactions:
 * 1. @Transactional annotation
 * 2. withTransaction closure
 * 3. withNativeTransaction closure (explicit/redundant, always native)
 *
 * This eliminates the need to explicitly use withNativeTransaction when global config is enabled.
 */
class MongoGlobalNativeTransactionSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Order]
    }

    @Override
    Map getConfiguration() {
        [(MongoSettings.SETTING_NATIVE_TRANSACTIONS): true]
    }

    // ========================================================================
    // Test 1: @Transactional uses native transactions with global config
    // ========================================================================

    def "test @Transactional uses native transactions when globally enabled"() {
        given: "a service with @Transactional"
        def service = new OrderService()

        when: "calling a @Transactional method"
        def result = service.createOrderWithTransaction("ORD-001")

        then: "native transaction was used"
        result.usedNative == true
        result.hadClientSession == true
        result.hadActiveTransaction == true

        and: "order was created"
        Order.count() == 1
        Order.findByNumber("ORD-001") != null
    }

    def "test @Transactional rollback uses native transaction abort"() {
        given: "a service with @Transactional"
        def service = new OrderService()

        when: "transaction throws exception"
        try {
            service.createOrderWithError("ORD-002")
        } catch (RuntimeException e) {
            // Expected
        }

        then: "native transaction rolled back - no data persisted"
        Order.count() == 0
        Order.findByNumber("ORD-002") == null
    }

    // ========================================================================
    // Test 2: withTransaction uses native transactions with global config
    // ========================================================================

    def "test withTransaction uses native transactions when globally enabled"() {
        when: "using withTransaction with global config"
        def result = Order.withTransaction { status ->
            new Order(number: "ORD-003").save(flush: true)
            ClientSession session = MongoNativeTransactionContext.getNativeSession()

            return [
                usedNative: Order.isInNativeTransaction(),
                hadClientSession: session != null,
                hadActiveTransaction: session?.hasActiveTransaction()
            ]
        }

        then: "native transaction was used"
        result.usedNative == true
        result.hadClientSession == true
        result.hadActiveTransaction == true

        and: "order was created"
        Order.count() == 1
        Order.findByNumber("ORD-003") != null
    }

    def "test withTransaction rollback uses native transaction abort"() {
        when: "withTransaction throws exception"
        try {
            Order.withTransaction { status ->
                new Order(number: "ORD-004").save(flush: true)
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException e) {
            // Expected
        }

        then: "native transaction rolled back - no data persisted"
        Order.count() == 0
        Order.findByNumber("ORD-004") == null
    }

    // ========================================================================
    // Test 3: withNativeTransaction explicitly uses native (always works)
    // ========================================================================

    def "test withNativeTransaction explicitly uses native transactions"() {
        when: "using withNativeTransaction"
        def result = Order.withNativeTransaction { clientSession ->
            new Order(number: "ORD-005").save(flush: true)
            ClientSession session = MongoNativeTransactionContext.getNativeSession()

            return [
                usedNative: Order.isInNativeTransaction(),
                hadClientSession: session != null,
                hadActiveTransaction: clientSession.hasActiveTransaction(),
                receivedSession: clientSession != null
            ]
        }

        then: "native transaction was used"
        result.usedNative == true
        result.hadClientSession == true
        result.hadActiveTransaction == true
        result.receivedSession == true

        and: "order was created"
        Order.count() == 1
        Order.findByNumber("ORD-005") != null
    }

    def "test withNativeTransaction rollback uses native transaction abort"() {
        when: "withNativeTransaction throws exception"
        try {
            Order.withNativeTransaction { clientSession ->
                new Order(number: "ORD-006").save(flush: true)
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException e) {
            // Expected
        }

        then: "native transaction rolled back - no data persisted"
        Order.count() == 0
        Order.findByNumber("ORD-006") == null
    }

    // ========================================================================
    // Test 4: All three methods are equivalent with global config
    // ========================================================================

    def "test all three transaction methods behave identically with global config"() {
        given: "a service with @Transactional"
        def service = new OrderService()

        when: "creating orders with all three methods"
        // Method 1: @Transactional
        def result1 = service.createOrderWithTransaction("ORD-A")

        // Method 2: withTransaction
        def result2 = Order.withTransaction {
            new Order(number: "ORD-B").save(flush: true)
            ClientSession session = MongoNativeTransactionContext.getNativeSession()
            return [
                usedNative: Order.isInNativeTransaction(),
                hadClientSession: session != null
            ]
        }

        // Method 3: withNativeTransaction
        def result3 = Order.withNativeTransaction { session ->
            new Order(number: "ORD-C").save(flush: true)
            return [
                usedNative: Order.isInNativeTransaction(),
                hadClientSession: session != null
            ]
        }

        then: "all three used native transactions"
        result1.usedNative == true
        result2.usedNative == true
        result3.usedNative == true

        and: "all three created orders successfully"
        Order.count() == 3
        Order.findByNumber("ORD-A") != null
        Order.findByNumber("ORD-B") != null
        Order.findByNumber("ORD-C") != null
    }
}

@Entity
class Order {
    String number
}

/**
 * Service class to test @Transactional annotation with global native transactions
 */
class OrderService {

    @Transactional
    def createOrderWithTransaction(String number) {
        def order = new Order(number: number).save(flush: true)
        ClientSession session = MongoNativeTransactionContext.getNativeSession()

        return [
            usedNative: Order.isInNativeTransaction(),
            hadClientSession: session != null,
            hadActiveTransaction: session?.hasActiveTransaction(),
            orderId: order.id
        ]
    }

    @Transactional
    def createOrderWithError(String number) {
        new Order(number: number).save(flush: true)
        throw new RuntimeException("Simulated error")
    }
}
