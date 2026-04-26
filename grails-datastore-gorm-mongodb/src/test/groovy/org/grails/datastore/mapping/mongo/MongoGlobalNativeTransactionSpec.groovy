package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.grails.datastore.mapping.mongo.config.MongoSettings

/**
 * Tests to verify that when global native transactions are enabled via config:
 * <pre>
 * grails.mongodb.nativeTransactionsEnabled: true
 * </pre>
 *
 * The withTransaction method automatically uses native MongoDB transactions instead of
 * session-only transactions.
 *
 * NOTE: @Transactional annotation testing cannot be done in unit tests because it requires
 * a full Spring application context with AOP proxy creation, PlatformTransactionManager bean,
 * and proper component scanning. These tests focus on GORM's withTransaction behavior which
 * can be tested in the unit test environment.
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
    // Test 1: withTransaction uses native transactions with global config
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
    // Test 2: withNativeTransaction explicitly uses native (always works)
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
    // Test 3: Both methods are equivalent with global config
    // ========================================================================

    def "test both withTransaction and withNativeTransaction behave identically with global config"() {
        when: "creating orders with both methods"
        // Method 1: withTransaction (should use native automatically with global config)
        def result1 = Order.withTransaction {
            new Order(number: "ORD-A").save(flush: true)
            ClientSession session = MongoNativeTransactionContext.getNativeSession()
            return [
                usedNative: Order.isInNativeTransaction(),
                hadClientSession: session != null
            ]
        }

        // Method 2: withNativeTransaction (explicitly native)
        def result2 = Order.withNativeTransaction { session ->
            new Order(number: "ORD-B").save(flush: true)
            return [
                usedNative: Order.isInNativeTransaction(),
                hadClientSession: session != null
            ]
        }

        then: "both used native transactions"
        result1.usedNative == true
        result1.hadClientSession == true
        result2.usedNative == true
        result2.hadClientSession == true

        and: "both created orders successfully"
        Order.count() == 2
        Order.findByNumber("ORD-A") != null
        Order.findByNumber("ORD-B") != null
    }
}

@Entity
class Order {
    String number
}
