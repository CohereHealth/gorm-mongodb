package org.grails.datastore.gorm.mongo.api

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoNativeCodecSession
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import org.grails.datastore.mapping.transactions.SessionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Trait providing common native transaction functionality for MongoDB GORM APIs.
 * Eliminates code duplication between MongoNativeStaticApi and MongoNativeInstanceApi.
 */
@CompileStatic
trait MongoNativeTransactionSupport<D> {
    
    abstract Datastore getDatastore()
    
    /**
     * Executes a closure within a native MongoDB transaction.
     * Pushes a MongoNativeCodecSession onto the session holder so that
     * getCurrentSession() returns the native session during the transaction.
     */
    D withNativeTransaction(Closure callable) {
        if (nativeSession) {
            ClientSession existing = currentNativeSession
            try {
                return (D) callable.call(existing)
            } catch (Exception e) {
                if (existing.hasActiveTransaction()) {
                    existing.abortTransaction()
                }
                throw e
            }
        }
        
        final MongoDatastore mongoDatastore = (MongoDatastore) getDatastore()
        def mongoClient = mongoDatastore.mongoClient
        ClientSession clientSession = null
        MongoNativeCodecSession nativeCodecSession = null
        
        try {
            clientSession = mongoClient.startSession()
            clientSession.startTransaction()
            MongoNativeTransactionContext.pushNativeSession(clientSession)
            
            // Push a native session onto the holder so getCurrentSession() returns it
            nativeCodecSession = new MongoNativeCodecSession(mongoDatastore, mongoDatastore.mappingContext, mongoDatastore.applicationEventPublisher, false)
            SessionHolder holder = (SessionHolder) TransactionSynchronizationManager.getResource(mongoDatastore)
            if (holder != null) {
                holder.addSession(nativeCodecSession)
            }
            
            D result = (D) callable.call(clientSession)
            clientSession.commitTransaction()
            return result
        } catch (Exception e) {
            if (clientSession?.hasActiveTransaction()) {
                clientSession.abortTransaction()
            }
            throw e
        } finally {
            MongoNativeTransactionContext.popNativeSession()
            if (nativeCodecSession != null) {
                SessionHolder holder = (SessionHolder) TransactionSynchronizationManager.getResource(mongoDatastore)
                if (holder != null) {
                    holder.removeSession(nativeCodecSession)
                }
            }
            clientSession?.close()
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