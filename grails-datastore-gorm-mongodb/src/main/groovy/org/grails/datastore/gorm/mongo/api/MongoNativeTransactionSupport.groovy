package org.grails.datastore.gorm.mongo.api

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoNativeCodecSession
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import org.grails.datastore.mapping.transactions.SessionHolder
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Trait providing native MongoDB transaction execution for GORM static and instance APIs.
 *
 * <p>Mixed into {@link MongoNativeStaticApi} and {@link MongoNativeInstanceApi} so that
 * {@code DomainClass.withNativeTransaction} and per-instance operations share the same
 * transaction lifecycle logic.</p>
 *
 * <h3>Reuse path</h3>
 * <p>When a native session already exists on the current thread (checked via
 * {@link org.grails.datastore.mapping.mongo.MongoNativeTransactionContext}), the closure
 * is executed within that session. If the closure throws, the transaction is aborted
 * immediately so that subsequent queries within the same test or request see the
 * rollback. Without the abort the writes would remain visible through the same
 * {@code ClientSession} until the session is closed.</p>
 *
 * <h3>New-session path</h3>
 * <p>When no native session exists, a new {@code ClientSession} is started, a transaction
 * is begun, and a {@link org.grails.datastore.mapping.mongo.MongoNativeCodecSession} is
 * pushed onto the bound {@link SessionHolder} so that {@code getCurrentSession()} returns
 * the native-aware session for the duration of the closure. On success the transaction is
 * committed; on failure it is aborted. The session and context are cleaned up in a
 * {@code finally} block.</p>
 *
 * @since 6.x
 * @see org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
 * @see org.grails.datastore.mapping.mongo.MongoDatastore#withNativeTransaction
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
                def session = DatastoreUtils.getSession(getDatastore(), false)
                if (session != null) {
                    session.clear()
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
            if (nativeCodecSession != null) {
                nativeCodecSession.clear()
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