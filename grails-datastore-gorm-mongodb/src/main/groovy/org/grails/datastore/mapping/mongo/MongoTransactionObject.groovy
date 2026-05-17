package org.grails.datastore.mapping.mongo

import com.mongodb.TransactionOptions
import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.mapping.transactions.Transaction
import org.springframework.lang.Nullable
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionSynchronizationUtils

@Slf4j
@CompileStatic
class MongoTransactionObject implements Transaction<ClientSession> {

    private @Nullable MongoSessionHolder mongoSessionHolder
    private boolean active = true
    private boolean rollbackOnly = false
    private final boolean isNested = false

    // Annotation support: track transaction ownership for cleanup
    boolean pushedToContext = false  // Did we push ClientSession to thread-local context?
    boolean boundResource = false     // Did we bind SessionHolder to Spring?
    ClientSession suspendedContextSession = null  // For REQUIRES_NEW: suspended outer session

    MongoTransactionObject(MongoSessionHolder mongoSessionHolder) {
        this.mongoSessionHolder = mongoSessionHolder
    }

    void setMongoSessionHolder(MongoSessionHolder holder) {
        this.mongoSessionHolder = holder
    }

    @Nullable
    MongoSessionHolder getMongoSessionHolder() {
        return mongoSessionHolder
    }

    @Override
    ClientSession getNativeTransaction() {
        return getClientSession()
    }
    
    @Override
    boolean isActive() {
        return active && getClientSession().hasActiveTransaction()
    }
    
    @Override
    void setTimeout(int timeout) {
        // MongoDB doesn't support per-transaction timeouts
    }
    
    @Override
    void commit() throws TransactionException {
        log.debug("MongoTransactionObject.commit() called: active={}, rollbackOnly={}", active, rollbackOnly)
        if (!active) {
            log.warn("Attempted to commit inactive transaction")
            throw new NoTransactionException("Transaction is not active")
        }
        if (rollbackOnly) {
            log.debug("Transaction marked for rollback only, rolling back instead of committing")
            rollback()
            return
        }
        final ClientSession clientSession = getRequiredClientSession()
        try {
            if (!isNested && clientSession.hasActiveTransaction()) {
                clientSession.commitTransaction()
                log.debug("Committed native MongoDB transaction")
            } else {
                log.debug("Skipping commit for nested transaction")
            }
        } finally {
            active = false
            if (!isNested) {
                clientSession.close()
                log.debug("Closed MongoDB client session")
            }
        }
    }
    
    @Override
    void rollback() throws TransactionException {
        if (!active) {
            log.debug("Transaction already inactive, skipping rollback")
            return
        }
        final ClientSession clientSession = getRequiredClientSession()
        try {
            if (!isNested && clientSession.hasActiveTransaction()) {
                clientSession.abortTransaction()
                log.debug("Rolled back native MongoDB transaction")
            } else {
                log.debug("Skipping rollback for nested transaction")
            }
        } finally {
            active = false
            if (!isNested) {
                clientSession.close()
                log.debug("Closed MongoDB client session after rollback")
            }
        }
    }

    final boolean hasMongoSessionHolder() {
        return mongoSessionHolder != null
    }

    boolean isRollbackOnly() {
        return getMongoSessionHolder()?.isRollbackOnly() ?: false
    }

    void startTransaction(@Nullable TransactionOptions options) {
        ClientSession session = getRequiredClientSession()
        if (options != null) {
            session.startTransaction(options)
        } else {
            session.startTransaction()
        }
    }

    void commitTransaction() {
        getRequiredClientSession().commitTransaction()
    }

    void abortTransaction() {
        getRequiredClientSession().abortTransaction()
        TransactionSynchronizationUtils.triggerFlush()
    }

    void closeSession() {
        getRequiredClientSession().close()
        TransactionSynchronizationUtils.triggerFlush()
    }
    
    void setRollbackOnly() {
        log.debug("Setting rollbackOnly=true on MongoTransactionObject")
        rollbackOnly = true
    }

    MongoSessionHolder getRequiredMongoSessionHolder() {
        MongoSessionHolder resourceHolder = getMongoSessionHolder()
        if (resourceHolder == null) {
            throw new IllegalStateException("No MongoResourceHolder available")
        }
        return resourceHolder
    }

    @Nullable
    ClientSession getClientSession() {
        return (ClientSession) mongoSessionHolder?.getClientSession()
    }

    ClientSession getRequiredClientSession() {
        ClientSession clientSession = getClientSession()
        if (clientSession == null) {
            throw new IllegalStateException("No Mongo ClientSession available")
        }
        return clientSession
    }
}