package example

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import spock.lang.Specification

@Integration
@Rollback
class ProviderServiceIntegrationSpec extends Specification {

    ProviderService providerService

    void "test create provider with native transaction"() {
        when: "creating a provider with native transaction"
        def provider = providerService.createProviderWithNativeTransaction("John", "Doe", 30)

        then: "provider is created successfully and visible within transaction"
        provider != null
        provider.id != null
        provider.firstName == "John"
        provider.lastName == "Doe"
        provider.age == 30
        Provider.count() == 1
        Provider.get(provider.id) != null
        // Transaction will rollback automatically after this test
    }

    void "test update provider with native transaction"() {
        given: "an existing provider"
        def provider = new Provider(firstName: "Jane", lastName: "Smith", age: 25).save(flush: true)

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
        def provider = new Provider(firstName: "Bob", lastName: "Johnson", age: 35).save(flush: true)
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
        createdProviders.every { it.id != null }
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
        given: "initial state"
        boolean contextDetected = false

        when: "executing within native transaction"
        Provider.withNativeTransaction { session ->
            contextDetected = MongoNativeTransactionContext.isInNativeTransaction()
            new Provider(firstName: "Context", lastName: "Test", age: 40).save(flush: true)
        }

        then: "native transaction context is detected"
        contextDetected == true
        Provider.findByFirstName("Context") != null
    }

    void "test immediate execution in native transaction"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "creating provider in native transaction"
        Provider.withNativeTransaction { session ->
            def provider = new Provider(firstName: "Immediate", lastName: "Test", age: 35)
            provider.save() // No flush needed - should execute immediately
            
            then: "provider is immediately available"
            Provider.count() == initialCount + 1
            Provider.findByFirstName("Immediate") != null
        }

        then: "provider persists after transaction"
        Provider.findByFirstName("Immediate") != null
    }

    void "test optimistic locking in native transaction"() {
        given: "a provider with version"
        def provider = new Provider(firstName: "Version", lastName: "Test", age: 30).save(flush: true)
        def originalVersion = provider.version

        when: "updating provider in native transaction"
        Provider.withNativeTransaction { session ->
            provider.age = 31
            provider.save()
        }

        then: "version is incremented"
        provider.version == originalVersion + 1

        and: "changes are persisted"
        def reloaded = Provider.get(provider.id)
        reloaded.age == 31
        reloaded.version == originalVersion + 1
    }

    void "test transaction isolation"() {
        given: "initial state"
        def initialCount = Provider.count()

        when: "creating provider in failed transaction"
        try {
            Provider.withNativeTransaction { session ->
                new Provider(firstName: "Isolated", lastName: "Test", age: 25).save(flush: true)
                throw new RuntimeException("Simulated failure")
            }
        } catch (RuntimeException e) {
            // Expected
        }

        then: "provider is not persisted due to rollback"
        Provider.count() == initialCount
        Provider.findByFirstName("Isolated") == null
    }
}