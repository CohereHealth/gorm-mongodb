package example

import org.grails.datastore.mapping.mongo.NativeTransactional
import org.springframework.transaction.annotation.Propagation

/**
 * Example service demonstrating @NativeTransactional annotation usage.
 */
@NativeTransactional  // Service-level: All methods use native transactions
class NativeTransactionalExampleService {

    /**
     * Creates a provider using the service-level @NativeTransactional.
     */
    Provider createProvider(Map data) {
        def provider = new Provider(data).save(failOnError: true)
        return provider
    }

    /**
     * Updates a provider using the service-level @NativeTransactional.
     */
    def updateProvider(Serializable id, Map updates) {
        def provider = Provider.get(id)
        updates.each { key, value ->
            provider[key] = value
        }
        provider.save(failOnError: true)
        return provider
    }

    /**
     * Creates a provider with explicit REQUIRED propagation.
     */
    @NativeTransactional(propagation = Propagation.REQUIRED)
    Provider createProviderRequired(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    /**
     * Creates an audit event with REQUIRES_NEW propagation.
     * Always creates a new independent transaction that commits
     * separately from the caller's transaction.
     */
    @NativeTransactional(propagation = Propagation.REQUIRES_NEW)
    AuditEvent createIndependentAudit(String entityId, String action) {
        return new AuditEvent(
            entityId: entityId,
            entityType: "Provider",
            action: action,
            performedBy: "SYSTEM",
            timestamp: new Date()
        ).save(failOnError: true)
    }

    /**
     * Creates provider with audit in independent transaction.
     * Demonstrates mixing propagation levels.
     */
    Map createProviderWithIndependentAudit(Map providerData) {
        // Uses service-level @NativeTransactional (REQUIRED)
        def provider = new Provider(providerData).save(failOnError: true)

        // Audit created in NEW independent transaction
        def audit = createIndependentAudit(provider.id.toString(), "CREATE")

        return [provider: provider, audit: audit]
    }

    /**
     * Creates provider with audit that rolls back independently.
     * Even if outer transaction fails, audit is already committed.
     */
    Map createProviderWithAuditThenFail(Map providerData) {
        def provider = new Provider(providerData).save(failOnError: true)

        // Audit committed in independent transaction
        createIndependentAudit(provider.id.toString(), "CREATE_ATTEMPT")

        // This failure rolls back provider, but NOT audit
        throw new RuntimeException("Simulated failure")
    }
}
