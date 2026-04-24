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
 * Spring {@link org.springframework.transaction.PlatformTransactionManager} that adds
 * native MongoDB transaction support on top of the standard GORM
 * {@link DatastoreTransactionManager}.
 *
 * <p>Each lifecycle method ({@code doGetTransaction}, {@code doBegin}, {@code doCommit},
 * {@code doRollback}, etc.) checks whether the current context requires a native
 * transaction and delegates to a native-specific implementation when it does,
 * falling back to the parent's Spring-managed behaviour otherwise.</p>
 *
 * <h3>Why every parent method needs an override</h3>
 * <p>{@link MongoTransactionObject} does not extend the core
 * {@link org.grails.datastore.mapping.transactions.TransactionObject}, and
 * {@link MongoSessionHolder} carries a {@code ClientSession} that the parent
 * knows nothing about. The parent methods cast unconditionally to
 * {@code TransactionObject}, so without overrides every suspend, resume,
 * rollback-only, and cleanup call would throw a {@code ClassCastException}.</p>
 *
 * <h3>Existing-transaction detection</h3>
 * <p>{@link #isExistingTransaction} checks {@code ClientSession.hasActiveTransaction()}
 * so that Spring's {@code AbstractPlatformTransactionManager} participates in an
 * already-running native transaction instead of attempting a second
 * {@code startTransaction}, which the MongoDB driver rejects with
 * {@code IllegalStateException("Transaction already in progress")}.</p>
 *
 * @see MongoTransactionObject
 * @see MongoSessionHolder
 * @see MongoNativeTransactionContext
 * @since 6.x
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
    protected Object doGetTransaction() throws TransactionException {
        if (shouldUseNativeTransaction()) {
            def existingResource = TransactionSynchronizationManager.getResource(datastore)
            if (existingResource instanceof MongoSessionHolder) {
                return new MongoTransactionObject((MongoSessionHolder) existingResource)
            }
            Session session = datastore.connect()
            ClientSession clientSession = mongoClient.startSession()
            return new MongoTransactionObject(new MongoSessionHolder(session, clientSession))
        }
        return super.doGetTransaction()
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
    protected boolean isExistingTransaction(Object transaction) {
        if (transaction instanceof MongoTransactionObject) {
            ClientSession session = ((MongoTransactionObject) transaction).getClientSession()
            return session != null && session.hasActiveTransaction()
        }
        return super.isExistingTransaction(transaction)
    }

    @Override
    protected Object doSuspend(Object transaction) throws TransactionException {
        if (transaction instanceof MongoTransactionObject) {
            TransactionSynchronizationManager.unbindResource(datastore)
            return ((MongoTransactionObject) transaction).mongoSessionHolder
        }
        return super.doSuspend(transaction)
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) throws TransactionException {
        if (suspendedResources instanceof MongoSessionHolder) {
            TransactionSynchronizationManager.bindResource(datastore, suspendedResources)
        } else {
            super.doResume(transaction, suspendedResources)
        }
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) throws TransactionException {
        if (status.transaction instanceof MongoTransactionObject) {
            ((MongoTransactionObject) status.transaction).setRollbackOnly()
            return
        }
        super.doSetRollbackOnly(status)
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
            final MongoSessionHolder sessionHolder = new MongoSessionHolder(session, clientSession)
            log.debug("Started native MongoDB transaction")

            sessionHolder.setTransaction(tx)
            if (!clientSession.hasActiveTransaction()) {
                tx.startTransaction(options)
            }

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
        MongoSessionHolder resourceHolder = new MongoSessionHolder(datastore.connect(), newClientSession(options));
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