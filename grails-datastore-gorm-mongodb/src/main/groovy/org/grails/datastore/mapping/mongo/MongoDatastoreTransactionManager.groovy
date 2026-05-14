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
            ClientSession session = holder.getClientSession()
            if (session != null) { // Native transaction
                if (session.hasActiveTransaction()) {
                    return new MongoTransactionObject(holder)
                } else {
                    // Stale native session
                    TransactionSynchronizationManager.unbindResource(getDatastore())
                    return new MongoTransactionObject(null)
                }
            } else { // Regular GORM transaction (no ClientSession)
                if (holder.getTransaction() != null) {
                    return new MongoTransactionObject(holder)
                } else {
                    // Stale regular session
                    TransactionSynchronizationManager.unbindResource(getDatastore())
                    return new MongoTransactionObject(null)
                }
            }
        }

        if (existingResource != null) {
            // Regular (non-native) transaction in progress
            org.grails.datastore.mapping.transactions.SessionHolder holder =
                (org.grails.datastore.mapping.transactions.SessionHolder) existingResource
            // Wrap the regular SessionHolder in a MongoSessionHolder for consistency
            MongoSessionHolder mongoHolder = new MongoSessionHolder(holder.session, null)
            return new MongoTransactionObject(mongoHolder)
        }

        // No existing resource - return an empty MongoTransactionObject
        return new MongoTransactionObject(null)
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        boolean useNative = shouldUseNativeTransaction() || isNativeTransactionalDefinition(definition)

        if (useNative) {
            validateNativePropagationLevel(definition)
            doBeginNative(transaction, definition)
        } else {
            // For regular transactions: create session, then delegate to parent's proven implementation
            MongoTransactionObject mongoTxObj = (MongoTransactionObject) transaction
            MongoSessionHolder sessionHolder = mongoTxObj.getMongoSessionHolder()

            // Check if resource already exists BEFORE creating new binding
            boolean resourceAlreadyBound = TransactionSynchronizationManager.hasResource(getDatastore())

            // Create session if it doesn't exist
            if (!sessionHolder) {
                Session session = getDatastore().connect()
                sessionHolder = new MongoSessionHolder(session, null)  // null ClientSession = regular transaction
                mongoTxObj.setMongoSessionHolder(sessionHolder)
            }

            // Set boundResource flag - adapter uses this to determine newSessionHolder
            // Parent's doBegin will bind the resource if isNewSessionHolder() is true
            mongoTxObj.boundResource = !resourceAlreadyBound

            // Adapt to parent-compatible transaction object and delegate
            super.doBegin(createParentCompatibleTransactionObject(mongoTxObj), definition)
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
     */
    private boolean isNativeTransactionalDefinition(TransactionDefinition definition) {
        if (definition instanceof TransactionAttribute) {
            String qualifier = ((TransactionAttribute) definition).getQualifier()
            boolean isNative = NativeTransactional.NATIVE_TRANSACTION_QUALIFIER.equals(qualifier)
            if (log.isDebugEnabled() && isNative) {
                log.debug("Detected @NativeTransactional via qualifier marker")
            }
            return isNative
        }
        return false
    }
    
    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        if (isNativeTransaction(status)) {
            doCommitNative(status)
        } else {
            MongoTransactionObject mongoTxObj = (MongoTransactionObject) status.transaction
            DefaultTransactionStatus adaptedStatus = createParentCompatibleTransactionStatus(mongoTxObj, status)
            super.doCommit(adaptedStatus)
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        if (isNativeTransaction(status)) {
            doRollbackNative(status)
        } else {
            MongoTransactionObject mongoTxObj = (MongoTransactionObject) status.transaction
            DefaultTransactionStatus adaptedStatus = createParentCompatibleTransactionStatus(mongoTxObj, status)
            super.doRollback(adaptedStatus)
        }
    }
    
    @Override
    protected boolean isExistingTransaction(Object transaction) {
        if (transaction instanceof MongoTransactionObject) {
            MongoTransactionObject txObject = (MongoTransactionObject) transaction

            // Check for active native transaction (ClientSession)
            ClientSession session = txObject.getClientSession()
            if (session != null && session.hasActiveTransaction()) {
                return true
            }

            // Check for programmatic native transaction in context
            if (MongoNativeTransactionContext.hasNativeSession()) {
                ClientSession contextSession = MongoNativeTransactionContext.getNativeSession()
                if (contextSession != null && contextSession.hasActiveTransaction() &&
                    TransactionSynchronizationManager.hasResource(getDatastore())) {
                    return true
                }
            }

            // Check for regular GORM transaction (SessionHolder)
            MongoSessionHolder holder = txObject.getMongoSessionHolder()
            if (holder != null && holder.getTransaction() != null) {
                return true
            }

            return false
        }
        return super.isExistingTransaction(transaction)
    }

    @Override
    protected Object doSuspend(Object transaction) throws TransactionException {
        if (transaction instanceof MongoTransactionObject) {
            MongoTransactionObject txObject = (MongoTransactionObject) transaction

            // Unbind the SessionHolder
            TransactionSynchronizationManager.unbindResource(getDatastore())

            // ALWAYS suspend the context session if one exists
            // This is necessary for REQUIRES_NEW to work correctly when called from
            // programmatic transactions
            ClientSession suspendedSession = null
            if (MongoNativeTransactionContext.hasNativeSession()) {
                suspendedSession = MongoNativeTransactionContext.popNativeSession()
            }
            return new SuspendedResourcesHolder(txObject.mongoSessionHolder, suspendedSession)
        }
        return super.doSuspend(transaction)
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) throws TransactionException {
        if (suspendedResources instanceof SuspendedResourcesHolder) {
            SuspendedResourcesHolder holder = (SuspendedResourcesHolder) suspendedResources
            // Resume the context session if it was suspended
            if (holder.suspendedSession != null) {
                MongoNativeTransactionContext.pushNativeSession(holder.suspendedSession)
            }
            // Rebind the SessionHolder only if not already bound
            if (holder.sessionHolder != null && !TransactionSynchronizationManager.hasResource(getDatastore())) {
                TransactionSynchronizationManager.bindResource(getDatastore(), holder.sessionHolder)
            }
        } else if (suspendedResources instanceof MongoSessionHolder) {
            TransactionSynchronizationManager.bindResource(getDatastore(), suspendedResources)
        } else {
            super.doResume(transaction, suspendedResources)
        }
    }

    /**
     * Holder for suspended resources (both SessionHolder and ClientSession)
     */
    private static class SuspendedResourcesHolder {
        final MongoSessionHolder sessionHolder
        final ClientSession suspendedSession

        SuspendedResourcesHolder(MongoSessionHolder sessionHolder, ClientSession suspendedSession) {
            this.sessionHolder = sessionHolder
            this.suspendedSession = suspendedSession
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
            MongoTransactionObject mongoTxObj = (MongoTransactionObject) transaction
            Object adaptedTransaction = createParentCompatibleTransactionObject(mongoTxObj)
            super.doCleanupAfterCompletion(adaptedTransaction)
        }
    }
    
    /**
     * Determines if native transactions should be used based on global native transaction setting only.
     * Note: We do NOT check MongoNativeTransactionContext here because:
     * - Spring-managed transactions (@Transactional/@NativeTransactional) should be determined by annotation only
     * - Programmatic transactions (withNativeTransaction) are handled separately and don't go through doBegin()
     * - Checking hasNativeSession() here would cause regular @Transactional to incorrectly use native transactions
     */
    private boolean shouldUseNativeTransaction() {
        return ((MongoDatastore) getDatastore()).isNativeTransactionsEnabled()
    }
    
    /**
     * Checks if the current transaction is a native transaction
     */
    private boolean isNativeTransaction(DefaultTransactionStatus status) {
        def txObject = status.transaction
        if (!(txObject instanceof MongoTransactionObject)) {
            return false
        }
        return ((MongoTransactionObject) txObject).clientSession != null
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

        boolean isRequiresNew = definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW
        ClientSession contextSession = MongoNativeTransactionContext.hasNativeSession() ?
                                      MongoNativeTransactionContext.getNativeSession() : null

        if (isRequiresNew && contextSession != null) {
            MongoNativeTransactionContext.popNativeSession()
            txObject.suspendedContextSession = contextSession
            contextSession = null
        }

        // Determine if we're joining an existing transaction:
        // - If sessionHolder exists from doGetTransaction(), we're joining
        // - OR if there's an active context session, we're joining
        // - NEVER join if REQUIRES_NEW (we already suspended above)
        boolean isJoiningExisting = !isRequiresNew && (
                                        sessionHolder != null ||
                                        (contextSession != null && contextSession.hasActiveTransaction())
                                    )

        // If no session holder exists OR REQUIRES_NEW, create one
        if (!sessionHolder || isRequiresNew) {
            ClientSession clientSession

            if (!isRequiresNew && contextSession != null && contextSession.hasActiveTransaction()) {
                // Join programmatic transaction if one exists and has active transaction
                clientSession = contextSession
            } else {
                // Create new ClientSession for independent transaction
                clientSession = mongoClient.startSession()
            }

            if (!isJoiningExisting) {
                MongoNativeTransactionContext.pushNativeSession(clientSession)
                txObject.pushedToContext = true
            } else {
                txObject.pushedToContext = false
            }

            Session session = getDatastore().connect()
            sessionHolder = new MongoSessionHolder(session, clientSession)
            txObject.setMongoSessionHolder(sessionHolder)
        } else {
            // Session holder already exists (joining existing transaction)
            if (!isJoiningExisting) {
                ClientSession clientSession = txObject.getClientSession()
                if (clientSession != null) {
                    MongoNativeTransactionContext.pushNativeSession(clientSession)
                    txObject.pushedToContext = true
                }
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
     * Commit native transaction
     */
    private void doCommitNative(DefaultTransactionStatus status) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(status.transaction)
        Transaction tx = txObject.mongoSessionHolder?.transaction
        // If pushedToContext=false, we're joining an outer transaction that will handle commit
        if (tx && txObject.pushedToContext) {
            tx.commit()
        }
    }
    
    /**
     * Rollback native transaction
     */
    private void doRollbackNative(DefaultTransactionStatus status) {
        final MongoTransactionObject txObject = extractMongoTransactionObject(status.transaction)
        Transaction tx = txObject.mongoSessionHolder?.transaction
        // If pushedToContext=false, we're joining an outer transaction that will handle rollback
        if (tx && txObject.pushedToContext) {
            tx.rollback()
        }
    }

    /**
     * Cleanup native transaction resources
     */
    private void doCleanupNative(MongoTransactionObject transaction) {
        // Only unbind if we bound the resource
        // If we're joining a programmatic transaction and didn't bind, leave it for the programmatic layer
        if (transaction.boundResource) {
            if (TransactionSynchronizationManager.hasResource(getDatastore())) {
                TransactionSynchronizationManager.unbindResource(getDatastore())
            }
        }

        // Only close and pop if we created the session (pushedToContext means we own it)
        // If joining a programmatic transaction, the programmatic layer owns the session
        if (transaction.pushedToContext) {
            transaction.mongoSessionHolder?.getClientSession()?.close()
            MongoNativeTransactionContext.popNativeSession()
        }

        // For REQUIRES_NEW: resume the suspended programmatic transaction
        if (transaction.suspendedContextSession != null) {
            MongoNativeTransactionContext.pushNativeSession(transaction.suspendedContextSession)
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

    private Object createParentCompatibleTransactionObject(MongoTransactionObject mongoTxObj) {
        if (mongoTxObj == null || mongoTxObj.mongoSessionHolder == null) {
            return new org.grails.datastore.mapping.transactions.TransactionObject()
        }

        if (mongoTxObj.cachedAdaptedTransactionObject != null) {
            return mongoTxObj.cachedAdaptedTransactionObject
        }

        org.grails.datastore.mapping.transactions.TransactionObject parentTxObj =
            new org.grails.datastore.mapping.transactions.TransactionObject()

        if (mongoTxObj.boundResource) {
            parentTxObj.setSession(mongoTxObj.mongoSessionHolder.getSession())
        } else {
            parentTxObj.setExistingSession(mongoTxObj.mongoSessionHolder.getSession())
        }
        parentTxObj.setSessionHolder(mongoTxObj.mongoSessionHolder)

        // Cache this adapted object for reuse in subsequent calls (commit, rollback, cleanup)
        mongoTxObj.cachedAdaptedTransactionObject = parentTxObj

        return parentTxObj
    }

    private DefaultTransactionStatus createParentCompatibleTransactionStatus(
            MongoTransactionObject mongoTxObj,
            DefaultTransactionStatus originalStatus) {
        Object parentCompatibleTxObj = createParentCompatibleTransactionObject(mongoTxObj)
        return new DefaultTransactionStatus(
            parentCompatibleTxObj,
            originalStatus.isNewTransaction(),
            originalStatus.isNewSynchronization(),
            originalStatus.isReadOnly(),
            originalStatus.isDebug(),
            originalStatus.getSuspendedResources()
        )
    }

}