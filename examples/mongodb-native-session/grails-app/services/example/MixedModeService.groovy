package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j

@Slf4j
class MixedModeService {

    /**
     * Legacy GORM transaction (uses @Transactional - Spring-managed)
     * Does NOT use native MongoDB transactions
     */
    @Transactional
    def createProviderLegacy(Map providerData) {
        log.info("Creating provider with LEGACY transaction mode")
        def provider = new Provider(providerData)
        provider.save(flush: true, failOnError: true)
        return provider
    }

    /**
     * Native MongoDB transaction
     */
    def createProviderNative(Map providerData) {
        log.info("Creating provider with NATIVE transaction mode")
        return Provider.withNativeTransaction { session ->
            def provider = new Provider(providerData)
            provider.save(failOnError: true)
            return provider
        }
    }

    /**
     * Mixed: Native transaction calling legacy operation
     */
    def createProviderNativeWithLegacyRead(Map providerData) {
        log.info("Native transaction with legacy read")
        return Provider.withNativeTransaction { session ->
            // Native write
            def provider = new Provider(providerData)
            provider.save(failOnError: true)

            // Legacy read (outside native context)
            def count = Provider.count()
            log.info("Provider count (legacy read): ${count}")

            return provider
        }
    }

    /**
     * Legacy transaction calling native operation
     */
    @Transactional
    def createProviderLegacyCallingNative(Map providerData, Map auditData) {
        log.info("Legacy transaction calling native operation")

        // Legacy write
        def provider = new Provider(providerData)
        provider.save(flush: true, failOnError: true)

        // Native transaction for audit
        def audit = AuditEvent.withNativeTransaction { session ->
            new AuditEvent(auditData).save(failOnError: true)
        }

        return [provider: provider, audit: audit]
    }
}
