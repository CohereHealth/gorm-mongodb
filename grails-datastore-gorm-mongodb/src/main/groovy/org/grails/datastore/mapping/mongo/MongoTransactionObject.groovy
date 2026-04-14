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
    private boolean isNested = false
    private boolean isNewTransaction = true

    MongoTransactionObject(MongoSessionHolder mongoSessionHolder) {
        this.mongoSessionHolder = mongoSessionHolder
    }

    MongoTransactionObject(MongoSessionHolder mongoSessionHolder, boolean isNewTransaction) {
        this.mongoSessionHolder = mongoSessionHolder
        this.isNewTransaction = isNewTransaction
    }

    @Nullable
    MongoSessionHolder getMongoSessionHolder() {
        return mongoSessionHolder
    }

    void setMongoSessionHolder(MongoSessionHolder mongoSessionHolder) {
        this.mongoSessionHolder = mongoSessionHolder
    }

    boolean isNewTransaction() {
        return isNewTransaction
    }

    void setNewTransaction(boolean isNewTransaction) {
        this.isNewTransaction = isNewTransaction
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
        if (!active) {
            log.warn("Attempted to commit inactive transaction")
            throw new NoTransactionException("Transaction is not active")
        }
        if (rollbackOnly) {
            log.debug("Transaction marked for rollback only, rolling back instead of committing")
            rollback()
            return
        }

        // Only commit and close if this is the outermost transaction
        if (!isNewTransaction) {
            log.debug("Skipping commit for nested transaction")
            return
        }

        final ClientSession clientSession = getRequiredClientSession()
        try {
            if (clientSession.hasActiveTransaction()) {
                clientSession.commitTransaction()
                log.debug("Committed native MongoDB transaction")
            }
        } finally {
            active = false
            clientSession.close()
            log.debug("Closed MongoDB client session")
        }
    }
    
    @Override
    void rollback() throws TransactionException {
        if (!active) {
            log.debug("Transaction already inactive, skipping rollback")
            return
        }

        // Only rollback and close if this is the outermost transaction
        if (!isNewTransaction) {
            log.debug("Skipping rollback for nested transaction")
            return
        }

        final ClientSession clientSession = getRequiredClientSession()
        try {
            if (clientSession.hasActiveTransaction()) {
                clientSession.abortTransaction()
                log.debug("Rolled back native MongoDB transaction")
            }
        } finally {
            active = false
            clientSession.close()
            log.debug("Closed MongoDB client session after rollback")
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