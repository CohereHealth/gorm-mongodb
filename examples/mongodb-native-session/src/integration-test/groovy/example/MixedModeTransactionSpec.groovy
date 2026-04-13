package example

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import spock.lang.Specification

@Integration
@Rollback
class MixedModeTransactionSpec extends Specification {

    MixedModeService mixedModeService

    void "test legacy transaction mode creates provider"() {
        when: "creating provider with legacy transaction"
        def provider = mixedModeService.createProviderLegacy([
            firstName: "Legacy",
            lastName: "Provider",
            age: 30
        ])

        then: "provider is created successfully"
        provider != null
        provider.id != null
        Provider.findByFirstName("Legacy") != null
    }

    void "test native transaction mode creates provider"() {
        when: "creating provider with native transaction"
        def provider = mixedModeService.createProviderNative([
            firstName: "Native",
            lastName: "Provider",
            age: 35
        ])

        then: "provider is created successfully"
        provider != null
        provider.id != null
        Provider.findByFirstName("Native") != null
    }

    void "test native and legacy modes can coexist"() {
        when: "creating providers with both modes"
        def legacyProvider = mixedModeService.createProviderLegacy([
            firstName: "Coexist",
            lastName: "Legacy",
            age: 28
        ])
        def nativeProvider = mixedModeService.createProviderNative([
            firstName: "Coexist",
            lastName: "Native",
            age: 32
        ])

        then: "both providers are created"
        legacyProvider != null
        nativeProvider != null
        Provider.count() == 2
        Provider.findByLastName("Legacy") != null
        Provider.findByLastName("Native") != null
    }

    void "test native transaction with legacy read operation"() {
        given: "existing providers"
        mixedModeService.createProviderLegacy([
            firstName: "Existing1",
            lastName: "Provider1",
            age: 25
        ])
        def initialCount = Provider.count()

        when: "creating provider with native transaction and legacy read"
        def provider = mixedModeService.createProviderNativeWithLegacyRead([
            firstName: "Mixed",
            lastName: "Read",
            age: 40
        ])

        then: "provider is created"
        provider != null
        Provider.count() == initialCount + 1
        Provider.findByFirstName("Mixed") != null
    }

    void "test legacy transaction calling native operation"() {
        given: "provider and audit data"
        def providerData = [
            firstName: "Legacy",
            lastName: "Caller",
            age: 45
        ]
        def auditData = [
            entityId: "test-entity",
            entityType: "Provider",
            action: "CREATE",
            performedBy: "test-user",
            timestamp: new Date()
        ]

        when: "legacy transaction calls native operation"
        def result = mixedModeService.createProviderLegacyCallingNative(providerData, auditData)

        then: "both entities are created"
        result.provider != null
        result.audit != null
        Provider.findByLastName("Caller") != null
        AuditEvent.findByEntityType("Provider") != null
    }

    void "test native transaction context detection"() {
        given: "flags to track context"
        boolean wasInNativeContext = false
        boolean wasOutsideNativeContext = false

        when: "checking context outside native transaction"
        wasOutsideNativeContext = MongoNativeTransactionContext.isInNativeTransaction()

        and: "checking context inside native transaction"
        Provider.withNativeTransaction { session ->
            wasInNativeContext = MongoNativeTransactionContext.isInNativeTransaction()
            new Provider(firstName: "Context", lastName: "Test", age: 50).save(flush: true, failOnError: true)
        }

        then: "context is correctly detected"
        !wasOutsideNativeContext
        wasInNativeContext
        Provider.findByFirstName("Context") != null
    }

    void "test independent rollback behavior between modes"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()
        def initialAuditCount = AuditEvent.count()

        when: "legacy transaction succeeds while native fails"
        def provider = mixedModeService.createProviderLegacy([
            firstName: "Independent",
            lastName: "Legacy",
            age: 33
        ])

        and: "native transaction fails"
        try {
            AuditEvent.withNativeTransaction { session ->
                new AuditEvent(
                    entityId: provider.id.toString(),
                    entityType: "Provider",
                    action: "CREATE",
                    performedBy: "system",
                    timestamp: new Date()
                ).save(flush: true, failOnError: true)

                throw new RuntimeException("Native transaction failure")
            }
        } catch (RuntimeException e) {
            // Expected
        }

        then: "legacy provider persists despite native failure"
        Provider.count() == initialProviderCount + 1
        Provider.findByFirstName("Independent") != null

        and: "native audit event is rolled back"
        AuditEvent.count() == initialAuditCount
    }
}
