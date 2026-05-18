package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.transactions.SessionHolder
import org.grails.datastore.mapping.transactions.Transaction

/**
 * Holds both a GORM {@link Session} and a MongoDB {@link ClientSession} for the duration
 * of a native transaction.
 *
 * <p>Extends {@link SessionHolder} rather than {@code ResourceHolderSupport} so that the
 * GORM core infrastructure ({@code DatastoreUtils.doGetSession},
 * {@code AbstractDatastore.getCurrentSession}) can cast the resource bound to
 * {@code TransactionSynchronizationManager} without a {@code ClassCastException}.
 * The GORM session satisfies the core's need for a session reference, while the
 * {@code ClientSession} is carried alongside for native transaction operations.</p>
 *
 * <p>The {@link #getTransaction()} method returns the native transaction if one has been
 * set, falling back to the GORM session's transaction otherwise. This allows the
 * transaction manager and session to retrieve the correct transaction object regardless
 * of whether the current context is native or Spring-managed.</p>
 *
 * @see MongoDatastoreTransactionManager
 * @see MongoNativeCodecSession
 * @since 6.x
 */
@CompileStatic
class MongoSessionHolder extends SessionHolder {
    
    private ClientSession clientSession
    private volatile Transaction nativeTransaction

    MongoSessionHolder(Session session, ClientSession clientSession) {
        super(session)
        this.clientSession = clientSession
    }
    
    ClientSession getClientSession() {
        return clientSession
    }
    
    @Override
    Transaction getTransaction() {
        return nativeTransaction != null ? nativeTransaction : super.getTransaction()
    }
    
    void setTransaction(Transaction transaction) {
        this.nativeTransaction = transaction
    }
    
    boolean hasNativeTransaction() {
        return clientSession != null && clientSession.hasActiveTransaction()
    }
}
