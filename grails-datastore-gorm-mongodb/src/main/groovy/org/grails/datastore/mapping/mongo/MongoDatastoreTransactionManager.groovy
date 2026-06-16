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
import org.springframework.transaction.interceptor.TransactionAttribute
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
        // Check if there's an existing resource bound to this thread
        def existingResource = TransactionSynchronizationManager.getResource(getDatastore())

        if (existingResource instanceof MongoSessionHolder) {
            MongoSessionHolder holder = (MongoSessionHolder) existingResource
            ClientSession clientSession = holder.getClientSession()

            if (clientSession == null) {
                // Regular (non-native) transaction — clientSession is null by design.
                // The holder is still valid; return it so the regular transaction path can join/reuse.
                return new MongoTransactionObject(holder)
            }

            if (clientSession.hasActiveTransaction()) {
                // Active native transaction — reuse it
                return new MongoTransactionObject(holder)
            }

            // Native session exists but has no active transaction — stale from a completed
            // native transaction. Unbind so a fresh transaction can start.
            TransactionSynchronizationManager.unbindResource(getDatastore())
            return new MongoTransactionObject(null)
        }

        if (existingResource != null) {
            // Regular SessionHolder (from GORM core, not MongoSessionHolder)
            org.grails.datastore.mapping.transactions.SessionHolder holder =
                (org.grails.datastore.mapping.transactions.SessionHolder) existingResource
            // Wrap the regular SessionHolder in a MongoSessionHolder for consistency
            MongoSessionHolder mongoHolder = new MongoSessionHolder(holder.session, null)
            return new MongoTransactionObject(mongoHolder)
        }

        // No existing resource - return an empty MongoTransactionObject
        // Determine in doBegin() whether to use native or regular transactions
        return new MongoTransactionObject(null)
    }
    
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        boolean useNative = shouldUseNativeTransaction() || isNativeTransactionalDefinition(definition)

        if (log.isDebugEnabled()) {
            log.debug("doBegin: useNative={}, isNativeTransactionalDef={}, propagation={}",
                useNative, isNativeTransactionalDefinition(definition), definition.getPropagationBehavior())
        }

        if (useNative) {
            validateNativePropagationLevel(definition)
            doBeginNative(transaction, definition)
        } else {
            doBeginRegular(transaction, definition)
        }
    }

    private void validateNativePropagationLevel(TransactionDefinition definition) {
        int propagation = definition.getPropagationBehavior()

        if (propagation != TransactionDefinition.PROPAGATION_REQUIRED &&
            propagation != TransactionDefinition.PROPAGATION_REQUIRES_NEW) {

            throw new UnsupportedOperationException(
                "Native MongoDB transactions (via @NativeTransactional) currently support only " +
                "REQUIRED and REQUIRES_NEW propagation levels. " +
                "Attempted to use propagation level: " + propagation + ". " +
                "For other propagation levels, please use standard @Transactional instead."
            )
        }
    }

    /**
     * Checks if the transaction definition indicates @NativeTransactional annotation was used.
     *
     * Note: Spring's TransactionInterceptor wraps our NativeTransactionAttribute in a
     * DelegatingTransactionAttribute (TransactionAspectSupport$1) that delegates to the original.
     * The instanceof check on the wrapper won't work. Instead, we use labels
     * which DelegatingTransactionAttribute delegates to the wrapped attribute.
     */
    private boolean isNativeTransactionalDefinition(TransactionDefinition definition) {
        // Direct check (works when called directly, not through TransactionInterceptor)
        if (definition instanceof NativeTransactionAttribute) {
            return true
        }
        // Check labels - DelegatingTransactionAttribute delegates getLabels() to the wrapped attribute
        if (definition instanceof TransactionAttribute) {
            Collection<String> labels = ((TransactionAttribute) definition).getLabels()
            if (labels != null && labels.contains(NativeTransactionAttribute.NATIVE_TX_NAME_PREFIX)) {
                return true
            }
        }
        return false
    }
    
    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        boolean isNative = isNativeTransaction(status)
        if (isNative) {
            doCommitNative(status)
        } else {
            doCommitRegular(status)
        }
    }
    
    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        if (log.isDebugEnabled()) {
            log.debug("doRollback: isNative={}", isNativeTransaction(status))
        }
        if (isNativeTransaction(status)) {
            doRollbackNative(status)
        } else {
            doRollbackRegular(status)
        }
    }
    
    @Override
    protected boolean isExistingTransaction(Object transaction) {
        if (transaction instanceof MongoTransactionObject) {
            ClientSession session = ((MongoTransactionObject) transaction).getClientSession()
            boolean existing = session != null && session.hasActiveTransaction()
            return existing
        }
        boolean parentResult = super.isExistingTransaction(transaction)
        return parentResult
    }

    @Override
    protected Object doSuspend(Object transaction) throws TransactionException {
        if (transaction instanceof MongoTransactionObject) {
            MongoSessionHolder holder = ((MongoTransactionObject) transaction).mongoSessionHolder
            TransactionSynchronizationManager.unbindResource(getDatastore())
            return holder
        }
        return super.doSuspend(transaction)
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) throws TransactionException {
        if (suspendedResources instanceof MongoSessionHolder) {
            TransactionSynchronizationManager.bindResource(getDatastore(), suspendedResources)
        } else {
            super.doResume(transaction, suspendedResources)
        }
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) throws TransactionException {
        if (status.transaction instanceof MongoTransactionObject) {
            if (log.isDebugEnabled()) {
                log.debug("Setting rollback-only flag on MongoTransactionObject")
            }
            ((MongoTransactionObject) status.transaction).setRollbackOnly()
            return
        }
        super.doSetRollbackOnly(status)
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(transaction)
        if (hasNativeSession(transaction)) {
            doCleanupNative(txObject)
        } else {
            doCleanupRegular(txObject)
        }
    }
    
    /**
     * Determines if native transactions should be used based on:
     * 1. Global native transaction setting
     * 2. Active native transaction context
     */
    private boolean shouldUseNativeTransaction() {
        return ((MongoDatastore) getDatastore()).isNativeTransactionsEnabled() ||
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
        final MongoTransactionObject txObject = extractMongoTransactionObject(transaction)
        MongoSessionHolder sessionHolder = txObject.getMongoSessionHolder()

        // Check if we're joining an existing transaction or need REQUIRES_NEW behavior
        ClientSession contextSession = MongoNativeTransactionContext.getNativeSession()
        boolean isJoiningExisting = (contextSession != null && contextSession.hasActiveTransaction())
        boolean isRequiresNew = (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW)


        // REQUIRES_NEW: suspend the outer transaction and force new session creation
        if (isRequiresNew && isJoiningExisting) {
            txObject.suspendedContextSession = contextSession
            MongoNativeTransactionContext.popNativeSession()
            contextSession = null
            isJoiningExisting = false
            // Spring reuses the same transaction object for REQUIRES_NEW, so it still carries
            // the outer MongoSessionHolder. We must discard it to create a fresh session.
            sessionHolder = null
        }

        // If no session holder exists, create one (session + ClientSession)
        if (!sessionHolder) {
            ClientSession clientSession

            if (!isRequiresNew && contextSession != null && contextSession.hasActiveTransaction()) {
                // Join programmatic transaction if one exists and has active transaction
                clientSession = contextSession
            } else {
                // Create new ClientSession for independent transaction
                clientSession = mongoClient.startSession()
            }

            // CRITICAL: Push ClientSession to context BEFORE creating the GORM session.
            // MongoDatastore.createSession() checks MongoNativeTransactionContext.hasNativeSession()
            // to decide whether to create a MongoNativeCodecSession (which uses ClientSession for ops)
            // or a regular MongoCodecSession (which queues operations).
            // If we push AFTER connect(), the wrong session type would be created.
            if (!isJoiningExisting) {
                MongoNativeTransactionContext.pushNativeSession(clientSession)
                txObject.pushedToContext = true
            } else {
                txObject.pushedToContext = false
            }

            // Now create the GORM session - it will see the native session in context
            // and create a MongoNativeCodecSession that uses ClientSession for all operations
            Session session = getDatastore().connect()

            sessionHolder = new MongoSessionHolder(session, clientSession)
            txObject.setMongoSessionHolder(sessionHolder)
        } else {
            // Session holder already exists - just handle context push
            if (!isJoiningExisting) {
                ClientSession clientSession = txObject.getClientSession()
                if (clientSession != null) {
                    MongoNativeTransactionContext.pushNativeSession(clientSession)
                }
                txObject.pushedToContext = true
            } else {
                txObject.pushedToContext = false
            }
        }

        final Session session = sessionHolder.getSession()
        final ClientSession clientSession = txObject.getClientSession()

        if (!clientSession) {
            throw new TransactionSystemException("ClientSession not available in transaction object")
        }

        final org.grails.datastore.mapping.transactions.Transaction gormTx = session.beginTransaction()
        sessionHolder.setTransaction(gormTx)

        final TransactionOptions options = buildTransactionOptions(definition)
        if (!clientSession.hasActiveTransaction()) {
            clientSession.startTransaction(options)
            log.debug("Started native MongoDB ClientSession transaction")
        }

        // Bind SessionHolder if not already bound, even when joining existing transaction
        // This ensures operations inside @NativeTransactional work when called from withNativeTransaction
        boolean alreadyBound = TransactionSynchronizationManager.hasResource(getDatastore())
        if (!alreadyBound) {
            TransactionSynchronizationManager.bindResource(getDatastore(), sessionHolder)
            // Only mark boundResource=true if we're NOT joining an existing transaction
            // If joining, the outer transaction owns the binding and will clean it up
            txObject.boundResource = !isJoiningExisting
        } else {
            txObject.boundResource = false
        }
    }

    /**
     * Builds MongoDB TransactionOptions from Spring's TransactionDefinition.
     * Applies timeout if specified in the transaction definition.
     *
     * <p>Note: MongoDB driver 4.x TransactionOptions doesn't directly support timeout configuration.
     * The timeout is primarily controlled via maxCommitTimeMS on the server side and
     * maxTimeMS on individual operations. Spring's transaction timeout is still tracked
     * and enforced by Spring's AbstractPlatformTransactionManager.</p>
     */
    private TransactionOptions buildTransactionOptions(TransactionDefinition definition) {
        TransactionOptions.Builder builder = TransactionOptions.builder()
        int timeoutSeconds = definition.getTimeout()
        if (timeoutSeconds > 0 && log.isDebugEnabled()) {
            log.debug("Transaction timeout of ${timeoutSeconds}s will be enforced by Spring transaction manager")
        }
        return builder.build()
    }

    /**
     * Begin regular (non-native) transaction.
     */
    private void doBeginRegular(Object transaction, TransactionDefinition definition) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(transaction)
        MongoSessionHolder sessionHolder = txObject.getMongoSessionHolder()

        try {
            // If no session holder exists, create one (session with no ClientSession)
            if (!sessionHolder) {
                Session session = getDatastore().connect()
                sessionHolder = new MongoSessionHolder(session, null)  // null ClientSession = regular transaction
                txObject.setMongoSessionHolder(sessionHolder)
            }

            final Session session = sessionHolder.getSession()

            // Handle read-only transactions
            if (definition.isReadOnly()) {
                session.setFlushMode(javax.persistence.FlushModeType.COMMIT)
            }

            // Start regular GORM transaction (not native MongoDB transaction)
            final org.grails.datastore.mapping.transactions.Transaction gormTx = session.beginTransaction()
            sessionHolder.setTransaction(gormTx)

            // Set timeout if specified
            int timeout = determineTimeout(definition)
            if (timeout != TransactionDefinition.TIMEOUT_DEFAULT) {
                gormTx.setTimeout(timeout)
            }

            if (!TransactionSynchronizationManager.hasResource(getDatastore())) {
                TransactionSynchronizationManager.bindResource(getDatastore(), sessionHolder)
                txObject.boundResource = true
                sessionHolder.setSynchronizedWithTransaction(true)
            } else {
                txObject.boundResource = false
            }
        } catch (Exception ex) {
            // Clean up on failure
            if (sessionHolder != null && sessionHolder.getSession() != null) {
                org.grails.datastore.mapping.core.DatastoreUtils.closeSession(sessionHolder.getSession())
            }
            throw new org.springframework.transaction.CannotCreateTransactionException(
                "Could not open GORM Session for transaction", ex)
        }
    }
    
    /**
     * Commit native transaction
     */
    private void doCommitNative(DefaultTransactionStatus status) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(status.transaction)

        // Only commit if we created the transaction (pushedToContext means we own it)
        if (txObject.pushedToContext) {
            txObject.commit()  // This checks rollbackOnly flag and calls clientSession.commitTransaction()

            // Clear GORM session cache after commit so subsequent queries see committed data
            org.grails.datastore.mapping.core.Session gormSession =
                org.grails.datastore.mapping.core.DatastoreUtils.getSession(getDatastore(), false)
            if (gormSession != null) {
                gormSession.clear()
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("doCommitNative: SKIPPING COMMIT - pushedToContext=false, joining outer transaction")
            }
        }
    }

    /**
     * Rollback native transaction
     */
    private void doRollbackNative(DefaultTransactionStatus status) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(status.transaction)
        // If pushedToContext=false, we're joining an outer transaction that will handle rollback
        if (log.isDebugEnabled()) {
            log.debug("doRollbackNative: pushedToContext={}", txObject.pushedToContext)
        }
        // Only rollback if we created the transaction (pushedToContext means we own it)
        if (txObject.pushedToContext) {
            log.debug("Rolling back native transaction via MongoTransactionObject")
            txObject.rollback()  // This calls clientSession.abortTransaction()
        }
    }

    /**
     * Commit regular (non-native) transaction
     */
    private void doCommitRegular(DefaultTransactionStatus status) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(status.transaction)
        Transaction tx = txObject.mongoSessionHolder?.transaction
        if (tx) {
            try {
                tx.commit()
            } catch (Exception ex) {
                throw new TransactionSystemException("Could not commit GORM transaction", ex)
            }
        } else if (log.isDebugEnabled()) {
            log.debug("No transaction to commit in regular transaction path")
        }
    }

    /**
     * Rollback regular (non-native) transaction
     */
    private void doRollbackRegular(DefaultTransactionStatus status) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(status.transaction)
        Transaction tx = txObject.mongoSessionHolder?.transaction
        if (tx) {
            try {
                tx.rollback()
            } catch (Exception ex) {
                throw new TransactionSystemException("Could not rollback GORM transaction", ex)
            }
        } else if (log.isDebugEnabled()) {
            log.debug("No transaction to rollback in regular transaction path")
        }
    }

    /**
     * Cleanup native transaction resources
     */
    private void doCleanupNative(MongoTransactionObject transaction) {
        // Only pop from context if we created the session (pushedToContext means we own it)
        if (transaction.pushedToContext) {
            MongoNativeTransactionContext.popNativeSession()
        }

        // For REQUIRES_NEW: resume the suspended programmatic transaction context
        if (transaction.suspendedContextSession != null) {
            MongoNativeTransactionContext.pushNativeSession(transaction.suspendedContextSession)
        }

        // Unbind the resource we bound during doBeginNative.
        if (transaction.boundResource) {
            if (TransactionSynchronizationManager.hasResource(getDatastore())) {
                TransactionSynchronizationManager.unbindResource(getDatastore())
            }
        }
    }

    /**
     * Cleanup regular transaction resources
     */
    private void doCleanupRegular(MongoTransactionObject transaction) {
        // Close the session if we created it
        MongoSessionHolder holder = transaction.getMongoSessionHolder()
        if (holder != null && transaction.boundResource) {
            Session session = holder.getSession()
            if (session != null) {
                org.grails.datastore.mapping.core.DatastoreUtils.closeSession(session)
            }
        }

        // Unbind the resource if we bound it
        if (transaction.boundResource) {
            TransactionSynchronizationManager.unbindResourceIfPossible(getDatastore())
        }
    }

    private MongoTransactionObject extractMongoTransactionObject(Object transaction) {
        if (transaction instanceof MongoTransactionObject) {
            MongoTransactionObject txObject = (MongoTransactionObject) transaction
            return txObject
        }
        throw new TransactionSystemException(String.format("Expected to find a %s but it turned out to be %s.", MongoTransactionObject.class, transaction.getClass()))
    }


    ClientSession getClientSession() {
        MongoSessionHolder resourceHolder = (MongoSessionHolder) TransactionSynchronizationManager.getResource(getDatastore())
        MongoTransactionObject txObject = new MongoTransactionObject(resourceHolder)
        return txObject != null ? txObject.getClientSession() : null
    }

    private MongoSessionHolder newResourceHolder(TransactionDefinition definition, ClientSessionOptions options) {
        MongoSessionHolder resourceHolder = new MongoSessionHolder(getDatastore().connect(), newClientSession(options));
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