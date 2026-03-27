package org.grails.datastore.gorm.mongo.api

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext

/**
 * Trait providing common native transaction functionality for MongoDB GORM APIs.
 * Eliminates code duplication between MongoNativeStaticApi and MongoNativeInstanceApi.
 */
@CompileStatic
trait MongoNativeTransactionSupport<D> {
    
    abstract Datastore getDatastore()
    
    /**
     * Executes a closure within a native MongoDB transaction.
     * Handles nested transactions by reusing existing sessions.
     */
    D withNativeTransaction(Closure callable) {
        if (nativeSession) {
            return (D) callable.call(currentNativeSession)
        }
        
        final MongoDatastore mongoDatastore = (MongoDatastore) getDatastore()
        def mongoClient = mongoDatastore.mongoClient
        def session = null
        
        try {
            session = mongoClient.startSession()
            session.startTransaction()
            MongoNativeTransactionContext.pushNativeSession(session)
            
            D result = (D) callable.call(session)
            session.commitTransaction()
            return result
        } catch (Exception e) {
            if (session?.hasActiveTransaction()) {
                session.abortTransaction()
            }
            throw e
        } finally {
            MongoNativeTransactionContext.popNativeSession()
            session?.close()
        }
    }

    /**
     * Gets the current native transaction session.
     */
    ClientSession getCurrentNativeSession() {
        return MongoNativeTransactionContext.getNativeSession()
    }
    
    /**
     * Checks if currently in a native transaction.
     */
    boolean isInNativeTransaction() {
        return MongoNativeTransactionContext.isInNativeTransaction()
    }

    boolean isNativeSession() {
        return MongoNativeTransactionContext.hasNativeSession()
    }

    boolean isNativeTransactionsEnabled() {
        return ((MongoDatastore) getDatastore()).isNativeTransactionsEnabled()
    }
}