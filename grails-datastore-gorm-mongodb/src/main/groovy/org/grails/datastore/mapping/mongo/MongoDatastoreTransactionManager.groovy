package org.grails.datastore.mapping.mongo

import com.mongodb.ClientSessionOptions
import com.mongodb.TransactionOptions
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.transactions.DatastoreTransactionManager
import org.grails.datastore.mapping.transactions.SessionHolder
import org.grails.datastore.mapping.transactions.Transaction
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionException
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Flexible MongoDB transaction manager that dynamically supports native transactions.
 * Extends DatastoreTransactionManager with native transaction capabilities when:
 * - Native transactions are globally enabled, OR
 * - Currently in a withNativeTransaction closure context
 */
@Slf4j
@CompileStatic
class MongoDatastoreTransactionManager extends DatastoreTransactionManager {
    
    private final MongoClient mongoClient
    
    MongoDatastoreTransactionManager(Datastore datastore, MongoClient mongoClient) {
        super()
        this.datastore = datastore
        this.mongoClient = mongoClient

    }
    
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        if (shouldUseNativeTransaction()) {
            doBeginNative(transaction, definition)
        } else {
            super.doBegin(transaction, definition)
        }
    }
    
    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        if (isNativeTransaction(status)) {
            doCommitNative(status)
        } else {
            super.doCommit(status)
        }
    }
    
    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        if (isNativeTransaction(status)) {
            doRollbackNative(status)
        } else {
            super.doRollback(status)
        }
    }
    
    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        if (hasNativeSession(transaction)) {
            doCleanupNative((MongoTransactionObject) transaction)
        } else {
            super.doCleanupAfterCompletion(transaction)
        }
    }
    
    /**
     * Determines if native transactions should be used based on:
     * 1. Global native transaction setting
     * 2. Active native transaction context
     */
    private boolean shouldUseNativeTransaction() {
        return ((MongoDatastore) datastore).isNativeTransactionsEnabled() ||
               MongoNativeTransactionContext.hasNativeSession()
    }
    
    /**
     * Checks if the current transaction is a native transaction
     */
    private boolean isNativeTransaction(DefaultTransactionStatus status) {
        def txObject = status.transaction
        return txObject.hasProperty('clientSession') && ((MongoTransactionObject) txObject).clientSession != null
    }
    
    /**
     * Checks if transaction object has native session
     */
    private boolean hasNativeSession(Object transaction) {
        return transaction.hasProperty('clientSession') && ((MongoTransactionObject) transaction).clientSession != null
    }
    
    /**
     * Begin native transaction
     */
    private void doBeginNative(Object transaction, TransactionDefinition definition) {
        final Session session = datastore.connect()
        final MongoTransactionObject tx = extractMongoTransactionObject(transaction)

        if (tx instanceof MongoTransactionObject) {
            final TransactionOptions options = TransactionOptions.builder().build()
            def clientSession = ((MongoTransactionObject) tx).getClientSession()
            final MongoSessionHolder sessionHolder = new MongoSessionHolder(clientSession)
            log.debug("Started native MongoDB transaction")

            sessionHolder.setTransaction(tx)
            tx.startTransaction(options)

            // Bind to Spring transaction manager
            TransactionSynchronizationManager.bindResource(datastore, sessionHolder)
        } else {
            final SessionHolder sessionHolder = new SessionHolder(session)
            log.debug("Started standard MongoDB transaction")

            sessionHolder.setTransaction(tx)

            // Bind to Spring transaction manager
            TransactionSynchronizationManager.bindResource(datastore, sessionHolder)
        }
    }
    
    /**
     * Commit native transaction
     */
    private void doCommitNative(DefaultTransactionStatus status) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(status.transaction)
        Transaction tx = txObject.mongoSessionHolder?.transaction
        if (tx) {
            tx.commit()
            log.debug("Committed native MongoDB transaction")
        }
    }
    
    /**
     * Rollback native transaction
     */
    private void doRollbackNative(DefaultTransactionStatus status) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(status.transaction)
        Transaction tx = txObject.mongoSessionHolder?.transaction
        if (tx) {
            tx.rollback()
            log.debug("Rolled back native MongoDB transaction")
        }
    }
    
    /**
     * Cleanup native transaction resources
     */
    private void doCleanupNative(MongoTransactionObject transaction) {
        // Unbind from Spring transaction manager
        TransactionSynchronizationManager.unbindResourceIfPossible(datastore)
        
        transaction.mongoSessionHolder?.getClientSession()?.close()

        log.debug("Cleaned up native MongoDB transaction resources")
    }

    private MongoTransactionObject extractMongoTransactionObject(Object transaction) {
        if (transaction instanceof MongoTransactionObject) {
            MongoTransactionObject txObject = (MongoTransactionObject) transaction
            return txObject
        }
        throw new TransactionSystemException(String.format("Expected to find a %s but it turned out to be %s.", MongoTransactionObject.class, transaction.getClass()))
    }


    ClientSession getClientSession() {
        MongoSessionHolder resourceHolder = (MongoSessionHolder) TransactionSynchronizationManager.getResource(datastore)
        MongoTransactionObject txObject = new MongoTransactionObject(resourceHolder)
        return txObject != null ? txObject.getClientSession() : null
    }

    private MongoSessionHolder newResourceHolder(TransactionDefinition definition, ClientSessionOptions options) {
        MongoSessionHolder resourceHolder = new MongoSessionHolder(newClientSession(options));
        return resourceHolder;
    }

    private ClientSession newClientSession(ClientSessionOptions options) {
        return getRequiredMongoClient().startSession(options)
    }

    private MongoClient getRequiredMongoClient() {
        if (mongoClient == null) {
            throw new IllegalStateException("No MongoClient specified");
        }
        return mongoClient;
    }

}