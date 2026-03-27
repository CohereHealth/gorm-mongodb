package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j

@Slf4j
@Transactional
class ProviderService {

    def createProviderWithNativeTransaction(String firstName, String lastName, Integer age) {
        log.info("Creating provider with native transaction: ${firstName} ${lastName}")

        return Provider.withNativeTransaction { session ->
            def provider = new Provider(firstName: firstName, lastName: lastName, age: age)
            provider.save(flush: true, failOnError: true)
            
            log.info("Provider created with ID: ${provider.id}")
            return provider
        }
    }

    def updateProviderWithNativeTransaction(Long providerId, Map updates) {
        log.info("Updating provider ${providerId} with native transaction")
        
        return Provider.withNativeTransaction { session ->
            def provider = Provider.get(providerId)
            if (!provider) {
                throw new IllegalArgumentException("Provider not found: ${providerId}")
            }
            
            updates.each { key, value ->
                provider[key] = value
            }
            
            provider.save(failOnError: true)
            log.info("Provider updated: ${provider}")
            return provider
        }
    }

    def deleteProviderWithNativeTransaction(Long providerId) {
        log.info("Deleting provider ${providerId} with native transaction")
        
        return Provider.withNativeTransaction { session ->
            def provider = Provider.get(providerId)
            if (!provider) {
                throw new IllegalArgumentException("Provider not found: ${providerId}")
            }
            
            provider.delete()
            log.info("Provider deleted: ${providerId}")
            return true
        }
    }

    def createMultipleProvidersWithRollback(List<Map> providerData, boolean shouldRollback = false) {
        log.info("Creating ${providerData.size()} providers with rollback test")
        
        try {
            return Provider.withNativeTransaction { session ->
                def createdProviders = []
                
                providerData.each { data ->
                    def provider = new Provider(data)
                    provider.save(failOnError: true)
                    createdProviders << provider
                    log.info("Created provider: ${provider}")
                }
                
                if (shouldRollback) {
                    log.info("Forcing rollback...")
                    throw new RuntimeException("Intentional rollback")
                }
                
                return createdProviders
            }
        } catch (Exception e) {
            log.error("Transaction failed: ${e.message}")
            throw e
        }
    }

    def testNestedNativeTransactions() {
        log.info("Testing nested native transactions")
        
        return Provider.withNativeTransaction { outerSession ->
            def provider1 = new Provider(firstName: "Outer", lastName: "Transaction", age: 30)
            provider1.save(failOnError: true)
            log.info("Created in outer transaction: ${provider1}")
            
            def provider2 = Provider.withNativeTransaction { innerSession ->
                def innerProvider = new Provider(firstName: "Inner", lastName: "Transaction", age: 25)
                innerProvider.save(failOnError: true)
                log.info("Created in inner transaction: ${innerProvider}")
                return innerProvider
            }
            
            return [provider1, provider2]
        }
    }
}