package example

import grails.testing.mixin.integration.Integration
import org.bson.types.ObjectId
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

@Integration
@NativeRollback
class ProviderServiceIntegrationSpec extends Specification {

    ProviderService providerService

    /**
     * Helper to check database state outside the current transaction.
     * Temporarily pops the native session to query committed state.
     */
    def checkOutsideTransaction(Closure check) {
        def result = null
        // Pop the native session temporarily to query outside transaction
        if (MongoNativeTransactionContext.hasNativeSession()) {
            def session = MongoNativeTransactionContext.popNativeSession()
            try {
                result = check.call()
            } finally {
                MongoNativeTransactionContext.pushNativeSession(session)
            }
        } else {
            result = check.call()
        }
        return result
    }

    void "test create provider with native transaction"() {
        when: "creating a provider with native transaction"
        def provider = providerService.createProviderWithNativeTransaction("John", "Doe", 30)

        then: "provider is created successfully"
        provider != null
        provider.id instanceof ObjectId
        provider.firstName == "John"
        provider.lastName == "Doe"
        provider.age == 30
        Provider.count() == 1
        Provider.get(provider.id) != null
    }

    void "test update provider with native transaction"() {
        given: "an existing provider"
        def provider = new Provider(firstName: "Jane", lastName: "Smith", age: 25).save(failOnError: true)

        when: "updating the provider with native transaction"
        def updatedProvider = providerService.updateProviderWithNativeTransaction(provider.id, [age: 26, firstName: "Janet"])

        then: "provider is updated successfully"
        updatedProvider.age == 26
        updatedProvider.firstName == "Janet"
        updatedProvider.lastName == "Smith"

        and: "changes are persisted"
        def reloaded = Provider.get(provider.id)
        reloaded.age == 26
        reloaded.firstName == "Janet"
    }

    void "test delete provider with native transaction"() {
        given: "an existing provider"
        def provider = new Provider(firstName: "Bob", lastName: "Johnson", age: 35).save(failOnError: true)
        def providerId = provider.id

        when: "deleting the provider with native transaction"
        def result = providerService.deleteProviderWithNativeTransaction(providerId)

        then: "provider is deleted successfully"
        result == true
        Provider.get(providerId) == null
        Provider.count() == 0
    }

    void "test multiple providers creation with successful transaction"() {
        given: "provider data"
        def providerData = [
            [firstName: "Alice", lastName: "Brown", age: 28],
            [firstName: "Charlie", lastName: "Davis", age: 32],
            [firstName: "Diana", lastName: "Wilson", age: 29]
        ]

        when: "creating multiple providers without rollback"
        def createdProviders = providerService.createMultipleProvidersWithRollback(providerData, false)

        then: "all providers are created"
        createdProviders.size() == 3
        Provider.count() == 3
        createdProviders.every { it.id instanceof ObjectId }
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
        checkOutsideTransaction { Provider.count() } == 0
    }

    void "test nested native transactions"() {
        when: "executing nested native transactions"
        def providers = providerService.testNestedNativeTransactions()

        then: "both providers are created"
        providers.size() == 2
        providers[0].firstName == "Outer"
        providers[1].firstName == "Inner"
        Provider.count() == 2

        and: "both providers exist in database"
        Provider.findByFirstName("Outer") != null
        Provider.findByFirstName("Inner") != null
    }

    void "test native transaction context detection"() {
        expect: "native transaction context is detected"
        MongoNativeTransactionContext.isInNativeTransaction()
    }

    void "test immediate execution in native transaction"() {
        when: "creating provider - should execute immediately without flush"
        def provider = new Provider(firstName: "Immediate", lastName: "Test", age: 35)
        provider.save()

        then: "provider is immediately available"
        provider.id instanceof ObjectId
        Provider.findByFirstName("Immediate") != null
    }

    void "test optimistic locking in native transaction"() {
        given: "a provider with version"
        def provider = new Provider(firstName: "Version", lastName: "Test", age: 30).save(failOnError: true)
        def originalVersion = provider.version

        when: "updating provider"
        provider.age = 31
        provider.save(failOnError: true)

        then: "version is incremented"
        provider.version == originalVersion + 1

        and: "changes are persisted"
        def reloaded = Provider.get(provider.id)
        reloaded.age == 31
        reloaded.version == originalVersion + 1
    }

    void "test transaction isolation"() {
        when: "creating provider in failed transaction"
        try {
            Provider.withNativeTransaction { session ->
                new Provider(firstName: "Isolated", lastName: "Test", age: 25).save(failOnError: true)
                throw new RuntimeException("Simulated failure")
            }
        } catch (RuntimeException e) {
            // Expected
        }

        then: "provider is not persisted due to rollback"
        checkOutsideTransaction { Provider.findByFirstName("Isolated") } == null
    }
}
