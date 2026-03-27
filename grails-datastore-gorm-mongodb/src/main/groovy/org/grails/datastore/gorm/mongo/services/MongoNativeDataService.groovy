package org.grails.datastore.gorm.mongo.services


import groovy.util.logging.Slf4j
import org.grails.datastore.gorm.mongo.api.MongoNativeTransactionSupport
import org.grails.datastore.mapping.mongo.MongoDatastore

@Slf4j
class MongoNativeDataService implements MongoNativeTransactionSupport {
    
    void enhanceDataService(Class serviceClass, MongoDatastore datastore) {
        if (datastore.isNativeTransactionsEnabled()) {
            serviceClass.metaClass.methodMissing = { String name, args ->
                if (name.startsWith('find') || name.startsWith('save') || name.startsWith('delete')) {
                    return executeInNativeTransaction(datastore) {
                        delegate.invokeMethod(name, args)
                    }
                }
                throw new MissingMethodException(name, serviceClass, args)
            }
        }
    }

    /**
     * Executes a closure within the appropriate transaction context.
     *
     * @param closure the closure to execute
     * @return the result of the closure execution
     */
    private <T> T executeInNativeTransaction(MongoDatastore datastore, Closure<T> closure) {
        def session = datastore.currentSession

        if (session.hasTransaction()) {
            log.trace("Executing within existing transaction for {}", persistentClass.simpleName)
            return closure.call()
        } else {
            log.trace("Starting new native transaction for {}", persistentClass.simpleName)
            return (T) withNativeTransaction(closure)
        }
    }

    @Override
    MongoDatastore getDatastore() {
        return null
    }
}