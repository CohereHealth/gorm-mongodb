package org.grails.datastore.gorm.mongo.api

import com.mongodb.MongoException
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

        return (D) executeNewNativeTransactionWithRetry(callable)
    }

    /**
     * Executes a closure within a new independent native MongoDB transaction.
     * Always starts a fresh {@code ClientSession} regardless of whether one already exists
     * (REQUIRES_NEW semantics). The outer transaction is suspended for the duration.
     * The inner transaction commits/aborts independently of any outer transaction.
     *
     * <p>Like {@link #withNativeTransaction}, the new transaction is retried on MongoDB
     * {@code TransientTransactionError}s — see {@link #executeNewNativeTransactionWithRetry}.</p>
     */
    D withNewNativeTransaction(Closure callable) {
        return (D) executeNewNativeTransactionWithRetry(callable)
    }

    /**
     * Runs {@code callable} in a brand-new native MongoDB transaction, retrying the whole
     * transaction on MongoDB {@code TransientTransactionError}s (e.g. {@code WriteConflict}/112,
     * {@code LockTimeout}/24) with bounded, jittered backoff — MongoDB's prescribed handling for
     * transient transaction aborts. Without this, such aborts surface as hard 500s even though
     * the driver labels them retryable.
     *
     * <p>A fresh {@code ClientSession} + transaction is started per attempt; on a transient
     * failure the transaction is aborted, the attempt's resources are fully cleaned up, and the
     * closure is re-executed after a short backoff. Non-transient failures (validation, genuine
     * optimistic-locking version conflicts, etc.) are rethrown immediately without retry.</p>
     *
     * <p>The commit is handled separately: a commit that fails with
     * {@code UnknownTransactionCommitResult} retries the <em>commit</em> (which is idempotent),
     * not the whole closure; a commit that fails with {@code TransientTransactionError} restarts
     * the whole transaction via the outer retry. This mirrors the driver's
     * {@code withTransaction(TransactionBody)} handling.</p>
     *
     * <p><strong>Caller contract:</strong> because the closure is re-executed on each retry, it
     * must contain only transactional MongoDB writes on this datastore. Those writes are rolled
     * back on abort and re-applied cleanly on retry (all-or-nothing), so no idempotency handling
     * is needed for them. Work that the transaction abort does NOT undo — writes to a different
     * MongoClient/datastore (e.g. the secondary {@code findingstore}), message/event publishes,
     * external HTTP calls, or an already-committed {@code REQUIRES_NEW} sub-transaction — must not
     * live inside the closure (or must be deferred until after commit), since retry will repeat it.</p>
     *
     * <p>Retry count and backoff are tunable via system properties
     * {@code gorm.mongodb.nativeTx.maxTransientRetries} (default 3),
     * {@code gorm.mongodb.nativeTx.retryBaseBackoffMs} (default 5) and
     * {@code gorm.mongodb.nativeTx.retryMaxBackoffMs} (default 50). Only the transaction-owning
     * path retries; a closure that joins an existing native session (see
     * {@link #withNativeTransaction}) is never retried here, because a joiner cannot restart the
     * enclosing transaction.</p>
     */
    private D executeNewNativeTransactionWithRetry(Closure callable) {
        final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(getClass())
        final MongoDatastore mongoDatastore = (MongoDatastore) getDatastore()
        def mongoClient = mongoDatastore.mongoClient
        final int maxAttempts = maxTransientTransactionRetries() + 1

        int attempt = 0
        while (true) {
            attempt++
            ClientSession clientSession = null
            MongoNativeCodecSession nativeCodecSession = null
            SessionHolder holder = null
            boolean createdHolder = false
            Exception failure = null

            try {
                clientSession = mongoClient.startSession()
                clientSession.startTransaction()
                MongoNativeTransactionContext.pushNativeSession(clientSession)

                // Push a native session onto the holder so getCurrentSession() returns it
                nativeCodecSession = new MongoNativeCodecSession(mongoDatastore, mongoDatastore.mappingContext, mongoDatastore.applicationEventPublisher, false)
                holder = (SessionHolder) TransactionSynchronizationManager.getResource(mongoDatastore)

                if (holder == null) {
                    // No existing holder, create one and bind it
                    holder = new SessionHolder(nativeCodecSession)
                    TransactionSynchronizationManager.bindResource(mongoDatastore, holder)
                    createdHolder = true
                } else {
                    holder.addSession(nativeCodecSession)
                }

                D result = (D) callable.call(clientSession)
                commitNativeTransactionWithRetry(clientSession, log)
                return result
            } catch (Exception e) {
                failure = e
                if (clientSession?.hasActiveTransaction()) {
                    try {
                        clientSession.abortTransaction()
                    } catch (Exception abortEx) {
                        // Preserve the original failure for the retry/throw decision below;
                        // surface the abort problem only in logs.
                        log.warn("Failed to abort native transaction after error (original error preserved): {}", abortEx.message)
                    }
                }
                if (nativeCodecSession != null) {
                    nativeCodecSession.clear()
                }
            } finally {
                MongoNativeTransactionContext.popNativeSession()

                if (nativeCodecSession != null) {
                    if (createdHolder) {
                        // We created the holder, so unbind it
                        TransactionSynchronizationManager.unbindResource(mongoDatastore)
                    } else if (holder != null) {
                        // Just remove our session from existing holder
                        holder.removeSession(nativeCodecSession)
                    }
                }

                clientSession?.close()
            }

            // Only reached when the attempt failed — a successful attempt returns from inside the try above.
            boolean isTransient = isTransientTransactionError(failure)
            if (isTransient && attempt < maxAttempts) {
                long backoffMs = computeRetryBackoffMillis(attempt)
                log.warn("Native transaction hit TransientTransactionError; retrying attempt {}/{} after {}ms: {}",
                        attempt, maxAttempts, backoffMs, failure.message)
                try {
                    Thread.sleep(backoffMs)
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt()
                    throw failure
                }
                continue
            }

            if (isTransient) {
                log.error("Native transaction retries exhausted after {} attempts on TransientTransactionError: {}",
                        attempt, failure.message)
            }
            throw failure
        }
    }

    /**
     * Commits the native transaction, retrying <em>only the commit</em> when the driver reports
     * {@code UnknownTransactionCommitResult} (the commit may or may not have applied; re-issuing
     * {@code commitTransaction()} is idempotent per the MongoDB spec). A commit that fails with
     * {@code TransientTransactionError} is rethrown so the caller's outer loop restarts the whole
     * transaction; any other failure is rethrown immediately.
     */
    private void commitNativeTransactionWithRetry(ClientSession clientSession, org.slf4j.Logger log) {
        final int maxCommitAttempts = maxTransientTransactionRetries() + 1
        int commitAttempt = 0
        while (true) {
            commitAttempt++
            try {
                clientSession.commitTransaction()
                return
            } catch (MongoException e) {
                if (e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                    // Whole-transaction retry is handled by the outer loop.
                    throw e
                }
                if (e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                        && commitAttempt < maxCommitAttempts) {
                    long backoffMs = computeRetryBackoffMillis(commitAttempt)
                    log.warn("Native transaction commit returned UnknownTransactionCommitResult; retrying commit {}/{} after {}ms: {}",
                            commitAttempt, maxCommitAttempts, backoffMs, e.message)
                    try {
                        Thread.sleep(backoffMs)
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                    continue
                }
                throw e
            }
        }
    }

    /**
     * Returns true if {@code t} (or anything in its cause chain) is a MongoDB
     * {@code TransientTransactionError} — the retryable label the server attaches to
     * transaction aborts such as {@code WriteConflict} (112) and {@code LockTimeout} (24).
     * Falls back to a message check so detection survives exception wrapping.
     */
    private boolean isTransientTransactionError(Throwable t) {
        Throwable current = t
        while (current != null) {
            if (current instanceof MongoException &&
                    ((MongoException) current).hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true
            }
            String msg = current.message
            if (msg != null && msg.contains(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private int maxTransientTransactionRetries() {
        Integer.getInteger("gorm.mongodb.nativeTx.maxTransientRetries", 3)
    }

    private long computeRetryBackoffMillis(int attempt) {
        long base = Long.getLong("gorm.mongodb.nativeTx.retryBaseBackoffMs", 5L)
        long cap = Long.getLong("gorm.mongodb.nativeTx.retryMaxBackoffMs", 50L)
        long exp = base * (1L << Math.min(attempt - 1, 16))
        long jitter = (long) (Math.random() * base)
        return Math.min(cap, exp) + jitter
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