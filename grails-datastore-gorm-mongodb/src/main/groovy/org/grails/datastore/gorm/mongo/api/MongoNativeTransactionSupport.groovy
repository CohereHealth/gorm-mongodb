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

    /** Default number of retries after the first attempt when retry is enabled via opts. */
    static final int DEFAULT_MAX_RETRIES = 3
    /** Default base backoff (ms) between retry attempts. */
    static final long DEFAULT_BASE_BACKOFF_MS = 5L
    /** Default maximum (capped) backoff (ms) between retry attempts. */
    static final long DEFAULT_MAX_BACKOFF_MS = 50L

    abstract Datastore getDatastore()
    
    /**
     * Executes a closure within a native MongoDB transaction, WITHOUT retry.
     *
     * <p>This no-argument form preserves the historical behaviour exactly: the owning transaction
     * runs a single attempt; on failure it is aborted and the exception propagates. Retry is
     * opt-in per call site via {@link #withNativeTransaction(Map, Closure)}.</p>
     *
     * <p>Pushes a MongoNativeCodecSession onto the session holder so that getCurrentSession()
     * returns the native session during the transaction. If a native session is already active the
     * closure joins it (and, being non-top-level, is never retried).</p>
     */
    D withNativeTransaction(Closure callable) {
        if (nativeSession) {
            return joinExistingNativeTransaction(callable)
        }
        // No opts => retry disabled (maxRetries = 0): single attempt, historical behaviour.
        return (D) executeNewNativeTransaction(callable, 0, DEFAULT_BASE_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS)
    }

    /**
     * Executes a closure within a native MongoDB transaction with a bounded, jittered retry on
     * MongoDB {@code TransientTransactionError}s (e.g. {@code WriteConflict}/112,
     * {@code LockTimeout}/24) — MongoDB's prescribed handling for transient transaction aborts.
     *
     * <p>Retry is a <strong>top-level</strong> (transaction-owning) concern only. If this call
     * instead joins an already-active native session, {@code opts} are ignored and a WARN is
     * logged, because a joiner cannot restart the enclosing transaction.</p>
     *
     * <p>Supported {@code opts} keys (all optional): {@code maxRetries} (retries after the first
     * attempt, default 3; {@code 0} disables), {@code baseBackoffMs} (default 5),
     * {@code maxBackoffMs} (default 50).</p>
     *
     * <p><strong>Caller contract:</strong> because the closure is re-executed on each retry, only
     * enable retry on clauses whose body is purely transactional MongoDB writes on this datastore
     * (rolled back on abort, re-applied cleanly on retry — all-or-nothing). Do NOT enable retry on
     * a clause that performs work the abort does not undo and the retry would repeat: writes to a
     * different MongoClient/datastore (e.g. the secondary {@code findingstore}), message/event
     * publishes, external HTTP/notification calls, or an already-committed {@code REQUIRES_NEW}
     * sub-transaction. Such clauses should use the no-argument {@link #withNativeTransaction(Closure)}.</p>
     *
     * <p>Usage: {@code Domain.withNativeTransaction(maxRetries: 3) { ... }}.</p>
     */
    D withNativeTransaction(Map opts, Closure callable) {
        if (nativeSession) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                    "Retry options {} ignored: this withNativeTransaction call joins an existing native transaction; retry applies only to the top-level (owning) transaction.",
                    opts)
            return joinExistingNativeTransaction(callable)
        }
        return (D) executeNewNativeTransaction(callable,
                optInt(opts, 'maxRetries', DEFAULT_MAX_RETRIES),
                optLong(opts, 'baseBackoffMs', DEFAULT_BASE_BACKOFF_MS),
                optLong(opts, 'maxBackoffMs', DEFAULT_MAX_BACKOFF_MS))
    }

    /**
     * Executes a closure within a new independent native MongoDB transaction (REQUIRES_NEW),
     * WITHOUT retry. Always starts a fresh {@code ClientSession} regardless of whether one already
     * exists; the outer transaction is suspended for the duration.
     */
    D withNewNativeTransaction(Closure callable) {
        return (D) executeNewNativeTransaction(callable, 0, DEFAULT_BASE_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS)
    }

    /**
     * {@link #withNewNativeTransaction(Closure)} with opt-in retry — same {@code opts} keys and
     * caller contract as {@link #withNativeTransaction(Map, Closure)}. This form always owns its
     * transaction (REQUIRES_NEW), so the opts always take effect.
     */
    D withNewNativeTransaction(Map opts, Closure callable) {
        return (D) executeNewNativeTransaction(callable,
                optInt(opts, 'maxRetries', DEFAULT_MAX_RETRIES),
                optLong(opts, 'baseBackoffMs', DEFAULT_BASE_BACKOFF_MS),
                optLong(opts, 'maxBackoffMs', DEFAULT_MAX_BACKOFF_MS))
    }

    /**
     * Runs {@code callable} inside an already-active native session (join path). Never retried:
     * a joiner cannot restart the enclosing transaction. On failure the shared transaction is
     * aborted so subsequent operations see the rollback, and the exception propagates.
     */
    private D joinExistingNativeTransaction(Closure callable) {
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

    /**
     * Runs {@code callable} in a brand-new native MongoDB transaction. When {@code maxRetries > 0}
     * the whole transaction is retried on {@code TransientTransactionError} (e.g.
     * {@code WriteConflict}/112, {@code LockTimeout}/24) with bounded, jittered backoff; when
     * {@code maxRetries <= 0} it runs exactly once (historical, no-retry behaviour). A commit that
     * fails with {@code UnknownTransactionCommitResult} retries only the (idempotent) commit; a
     * commit that fails with {@code TransientTransactionError} restarts the whole transaction via
     * the outer loop. Mirrors the driver's {@code withTransaction(TransactionBody)} handling.
     *
     * <p>On a transient failure the transaction is aborted, the attempt's resources are fully
     * cleaned up, and the closure is re-executed after a short backoff. Non-transient failures
     * (validation, genuine optimistic-locking version conflicts, etc.) are rethrown immediately.</p>
     *
     * <p>Retries are logged at INFO (and eventual success-after-retry at INFO) so they can be
     * monitored; exhausting the retry budget logs at ERROR before rethrowing.</p>
     */
    private D executeNewNativeTransaction(Closure callable, int maxRetries, long baseBackoffMs, long maxBackoffMs) {
        final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(getClass())
        final MongoDatastore mongoDatastore = (MongoDatastore) getDatastore()
        def mongoClient = mongoDatastore.mongoClient
        final boolean retryEnabled = maxRetries > 0
        final int maxAttempts = (retryEnabled ? maxRetries : 0) + 1

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
                commitNativeTransaction(clientSession, log, retryEnabled, maxRetries, baseBackoffMs, maxBackoffMs)
                if (attempt > 1) {
                    log.info("Native transaction succeeded after {} attempt(s) (recovered from a transient conflict).", attempt)
                }
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
            if (retryEnabled && isTransient && attempt < maxAttempts) {
                long backoffMs = computeRetryBackoffMillis(attempt, baseBackoffMs, maxBackoffMs)
                log.info("Native transaction hit TransientTransactionError; retrying attempt {}/{} after {}ms: {}",
                        attempt, maxAttempts, backoffMs, failure.message)
                try {
                    Thread.sleep(backoffMs)
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt()
                    throw failure
                }
                continue
            }

            if (retryEnabled && isTransient) {
                log.error("Native transaction retries exhausted after {} attempts on TransientTransactionError: {}",
                        attempt, failure.message)
            }
            throw failure
        }
    }

    /**
     * Commits the native transaction. When {@code retryEnabled}, a commit that fails with
     * {@code UnknownTransactionCommitResult} retries <em>only the commit</em> (re-issuing
     * {@code commitTransaction()} is idempotent per the MongoDB spec). A commit that fails with
     * {@code TransientTransactionError} is rethrown so the caller's outer loop restarts the whole
     * transaction; any other failure is rethrown immediately. When retry is disabled the commit is
     * attempted exactly once.
     */
    private void commitNativeTransaction(ClientSession clientSession, org.slf4j.Logger log,
                                         boolean retryEnabled, int maxRetries, long baseBackoffMs, long maxBackoffMs) {
        final int maxCommitAttempts = (retryEnabled ? maxRetries : 0) + 1
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
                if (retryEnabled && e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                        && commitAttempt < maxCommitAttempts) {
                    long backoffMs = computeRetryBackoffMillis(commitAttempt, baseBackoffMs, maxBackoffMs)
                    log.info("Native transaction commit returned UnknownTransactionCommitResult; retrying commit {}/{} after {}ms: {}",
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

    private int optInt(Map opts, String key, int dflt) {
        Object v = opts?.get(key)
        return v == null ? dflt : ((Number) v).intValue()
    }

    private long optLong(Map opts, String key, long dflt) {
        Object v = opts?.get(key)
        return v == null ? dflt : ((Number) v).longValue()
    }

    private long computeRetryBackoffMillis(int attempt, long baseBackoffMs, long maxBackoffMs) {
        long exp = baseBackoffMs * (1L << Math.min(attempt - 1, 16))
        long jitter = (long) (Math.random() * baseBackoffMs)
        return Math.min(maxBackoffMs, exp) + jitter
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