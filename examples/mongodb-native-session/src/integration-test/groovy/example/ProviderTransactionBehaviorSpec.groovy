package example

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/**
 * Tests for Provider transaction rollback behavior.
 * These tests verify that transactions properly rollback on failure.
 * No @NativeRollback annotation - we want to test actual database state after rollback.
 */
@Integration
class ProviderTransactionBehaviorSpec extends Specification {

    ProviderService providerService

    def cleanup() {
        // Manual cleanup after each test since we're not using @NativeRollback
        Provider.collection.drop()
    }

    void "test multiple providers creation with rollback"() {
        given: "provider data"
        def providerData = [
            [firstName: "Eve", lastName: "Miller", age: 27],
            [firstName: "Frank", lastName: "Garcia", age: 31]
        ]

        when: "creating multiple providers with rollback"
        providerService.createMultipleProvidersWithRollback(providerData, true)

        then: "transaction is rolled back"
        thrown(RuntimeException)
        Provider.count() == 0
    }

    void "test transaction isolation"() {
        when: "creating provider in failed transaction"
        Provider.withNativeTransaction { session ->
            new Provider(firstName: "Isolated", lastName: "Test", age: 25).save(failOnError: true)
            throw new RuntimeException("Simulated failure")
        }

        then: "exception is thrown"
        thrown(RuntimeException)

        and: "provider is not persisted due to rollback"
        Provider.findByFirstName("Isolated") == null
    }
}
