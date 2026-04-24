package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import org.grails.datastore.mapping.transactions.SessionHolder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

@Integration
class MixedModeTransactionSpec extends Specification {

    MixedModeService mixedModeService

    @Autowired
    Datastore mongoDatastore

    Session session
    SessionHolder holder

    def setup() {
        session = mongoDatastore.connect()
        holder = new SessionHolder(session)
        TransactionSynchronizationManager.bindResource(mongoDatastore, holder)
    }

    def cleanup() {
        if (holder) {
            TransactionSynchronizationManager.unbindResource(mongoDatastore)
        }
        session?.disconnect()

        Provider.collection.drop()
        AuditEvent.collection.drop()
    }

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

        when: "checking context inside native transaction"
        Provider.withNativeTransaction { session ->
            wasInNativeContext = MongoNativeTransactionContext.isInNativeTransaction()
            new Provider(firstName: "Context", lastName: "Test", age: 50).save(flush: true, failOnError: true)
        }

        then: "context is correctly detected"
        wasInNativeContext
        Provider.findByFirstName("Context") != null
    }

    void "test independent native transactions rollback separately"() {
        given: "provider created and explicitly committed"
        def providerId = null

        // Create provider in its own transaction to ensure it's committed
        Provider.withNativeTransaction { setupSession ->
            def provider = new Provider(
                firstName: "Independent",
                lastName: "Legacy",
                age: 33
            ).save(failOnError: true)
            providerId = provider.id
        }

        and: "verify provider was committed"
        def initialProviderCount = Provider.count()
        Provider.get(providerId) != null

        when: "native transaction for audit fails"
        try {
            AuditEvent.withNativeTransaction { session ->
                new AuditEvent(
                    entityId: providerId.toString(),
                    entityType: "Provider",
                    action: "CREATE",
                    performedBy: "system",
                    timestamp: new Date()
                ).save(failOnError: true)

                throw new RuntimeException("Native transaction failure")
            }
        } catch (RuntimeException e) {
            // Expected - audit transaction should rollback
        }

        then: "provider persists (setup transaction committed)"
        Provider.count() == initialProviderCount
        Provider.get(providerId) != null
        Provider.findByFirstName("Independent") != null

        and: "audit event was rolled back (native transaction aborted)"
        AuditEvent.findByEntityId(providerId.toString()) == null
    }

    void "test legacy save persists when subsequent native transaction fails"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()
        def initialAuditCount = AuditEvent.count()

        when: "legacy save followed by native transaction that fails"
        def providerId = null
        try {
            // LEGACY MODE: Provider saved without explicit native transaction
            def provider = new Provider(
                firstName: "LegacyFirst",
                lastName: "Test",
                age: 30
            ).save(flush: true, failOnError: true)
            providerId = provider.id

            // NATIVE TRANSACTION: AuditEvent fails
            AuditEvent.withNativeTransaction { session ->
                new AuditEvent(
                    entityId: providerId.toString(),
                    entityType: "Provider",
                    action: "CREATE",
                    performedBy: "system",
                    timestamp: new Date()
                ).save(failOnError: true)

                throw new RuntimeException("Native transaction fails!")
            }
        } catch (RuntimeException e) {
            // Expected - native transaction should rollback
        }

        then: "audit transaction rolls back"
        AuditEvent.count() == initialAuditCount

        and: "legacy provider was persisted and not rollback"
        Provider.get(providerId) != null
        Provider.count() == initialProviderCount + 1
        Provider.findByFirstName("LegacyFirst") != null
    }

    void "test native transaction commits independently before legacy failure"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()

        when: "native transaction followed by failing legacy operation"
        def providerId = null
        def auditId = null
        try {
            // NATIVE TRANSACTION: Provider saved and committed
            Provider.withNativeTransaction { session ->
                def provider = new Provider(
                    firstName: "NativeFirst",
                    lastName: "Test",
                    age: 35
                ).save(failOnError: true)
                providerId = provider.id
            }

            // LEGACY MODE: AuditEvent save followed by failure
            def audit = new AuditEvent(
                entityId: providerId.toString(),
                entityType: "Provider",
                action: "CREATE",
                performedBy: "system",
                timestamp: new Date()
            ).save(flush: true, failOnError: true)
            auditId = audit.id

            throw new RuntimeException("Legacy operation fails!")

        } catch (RuntimeException e) {
            // Expected failure
        }

        then: "native transaction provider persists"
        Provider.get(providerId) != null
        Provider.count() == initialProviderCount + 1
        Provider.findByFirstName("NativeFirst") != null

        and: "audit was saved before exception (MongoDB auto-commit)"
        AuditEvent.get(auditId) != null
        AuditEvent.findByEntityType("Provider") != null
    }
}
