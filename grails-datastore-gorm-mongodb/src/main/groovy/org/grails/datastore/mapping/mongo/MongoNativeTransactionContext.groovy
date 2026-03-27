package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Thread-local context manager for MongoDB native transaction sessions.
 * 
 * <h2>Purpose</h2>
 * <p>This class manages MongoDB ClientSession instances in a thread-local stack to support
 * nested native transactions and ensure proper session lifecycle management. It provides
 * the foundation for GORM's native MongoDB transaction support (MongoDB 4.0+).</p>
 * 
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Thread-Local Storage:</strong> Each thread maintains its own session stack</li>
 *   <li><strong>Nested Transaction Support:</strong> Stack-based design supports nested transactions</li>
 *   <li><strong>Automatic Cleanup:</strong> Provides methods for proper session lifecycle management</li>
 *   <li><strong>Transaction State Tracking:</strong> Monitors active transaction state</li>
 * </ul>
 * 
 * <h2>Usage Patterns</h2>
 * 
 * <h3>Basic Transaction Management</h3>
 * <pre>{@code
 * // Starting a native transaction
 * ClientSession session = mongoClient.startSession()
 * session.startTransaction()
 * MongoNativeTransactionContext.pushNativeSession(session)
 * 
 * try {
 *     // Perform database operations
 *     person.save()
 *     session.commitTransaction()
 * } catch (Exception e) {
 *     session.abortTransaction()
 *     throw e
 * } finally {
 *     MongoNativeTransactionContext.popNativeSession()
 *     session.close()
 * }
 * }</pre>
 * 
 * <h3>Checking Transaction State</h3>
 * <pre>{@code
 * // Check if in native transaction
 * if (MongoNativeTransactionContext.isInNativeTransaction()) {
 *     // Use immediate execution path
 *     executeImmediately()
 * } else {
 *     // Use traditional flush-based approach
 *     queueForFlush()
 * }
 * }</pre>
 * 
 * <h3>Nested Transaction Handling</h3>
 * <pre>{@code
 * // Outer transaction
 * Person.withTransaction { outerStatus ->
 *     person1.save() // Uses native session
 *     
 *     // Inner transaction (reuses same session)
 *     Person.withTransaction { innerStatus ->
 *         person2.save() // Same native session
 *     }
 * }
 * }</pre>
 * 
 * <h2>Integration with GORM</h2>
 * <p>This context is automatically managed by:</p>
 * <ul>
 *   <li>{@code MongoNativeStaticApi.withTransaction()}</li>
 *   <li>{@code MongoNativeInstanceApi.withNativeTransaction()}</li>
 *   <li>{@code MongoNativeCodecSession} for session selection</li>
 *   <li>{@code MongoNativeCodecEntityPersister} for immediate execution</li>
 * </ul>
 * 
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe through the use of ThreadLocal storage. Each thread
 * maintains its own independent session stack, preventing cross-thread interference.</p>
 * 
 * <h2>Memory Management</h2>
 * <p>Important: Always ensure proper cleanup of sessions to prevent memory leaks:</p>
 * <pre>{@code
 * // Always pair push/pop operations
 * MongoNativeTransactionContext.pushNativeSession(session)
 * try {
 *     // transaction work
 * } finally {
 *     MongoNativeTransactionContext.popNativeSession()
 *     session.close()
 * }
 * }</pre>
 * 
 * @author Puneet Behl
 * @since 6.x
 * @see MongoNativeCodecSession
 * @see org.grails.datastore.gorm.mongo.api.MongoNativeStaticApi
 * @see org.grails.datastore.gorm.mongo.api.MongoNativeInstanceApi
 */
@Slf4j
@CompileStatic
class MongoNativeTransactionContext {
    private static final ThreadLocal<Stack<ClientSession>> NATIVE_SESSION_STACK = new ThreadLocal<Stack<ClientSession>>() {
        @Override
        protected Stack<ClientSession> initialValue() {
            return new Stack<ClientSession>()
        }
    }
    
    /**
     * Pushes a MongoDB ClientSession onto the thread-local session stack.
     * 
     * <p>This method is called when starting a new native transaction or when
     * entering a nested transaction context. The session stack allows for proper
     * handling of nested transactions by maintaining the session hierarchy.</p>
     * 
     * <p><strong>Usage:</strong> Always pair with {@link #popNativeSession()} in a try-finally block.</p>
     * 
     * @param session the MongoDB ClientSession to push onto the stack
     * @throws IllegalArgumentException if session is null
     */
    static void pushNativeSession(ClientSession session) {
        if (session == null) {
            throw new IllegalArgumentException("ClientSession cannot be null")
        }
        NATIVE_SESSION_STACK.get().push(session)
        log.debug("Pushed native session to context stack, depth: {}", NATIVE_SESSION_STACK.get().size())
    }
    
    /**
     * Pops the top MongoDB ClientSession from the thread-local session stack.
     * 
     * <p>This method is called when completing a native transaction or when
     * exiting a nested transaction context. It returns the session that was
     * most recently pushed onto the stack.</p>
     * 
     * <p><strong>Important:</strong> The caller is responsible for properly closing
     * the returned session to prevent resource leaks.</p>
     * 
     * @return the ClientSession that was popped from the stack, or null if stack is empty
     */
    static ClientSession popNativeSession() {
        Stack<ClientSession> stack = NATIVE_SESSION_STACK.get()
        ClientSession session = stack.empty() ? null : stack.pop()
        log.debug("Popped native session from context stack, remaining depth: {}", stack.size())
        return session
    }
    
    /**
     * Gets the current MongoDB ClientSession without removing it from the stack.
     * 
     * <p>This method returns the session at the top of the thread-local stack,
     * which represents the currently active native transaction session. This is
     * used by GORM components to access the current transaction context.</p>
     * 
     * @return the current ClientSession, or null if no session is active
     */
    static ClientSession getNativeSession() {
        Stack<ClientSession> stack = NATIVE_SESSION_STACK.get()
        return stack.empty() ? null : stack.peek()
    }
    
    /**
     * Clears all sessions from the thread-local session stack.
     * 
     * <p><strong>Warning:</strong> This method should be used with caution as it
     * clears the entire session stack without properly closing sessions. It's
     * primarily intended for cleanup in error scenarios or testing.</p>
     * 
     * <p>Prefer using {@link #popNativeSession()} in a proper try-finally
     * pattern for normal transaction cleanup.</p>
     */
    static void clearNativeSession() {
        NATIVE_SESSION_STACK.get().clear()
        log.debug("Cleared all native sessions from context stack")
    }
    
    /**
     * Checks if there is an active native session in the current thread.
     * 
     * <p>This method is used throughout GORM to determine whether to use
     * native transaction behavior (immediate execution) or traditional
     * behavior (queued operations with flush).</p>
     * 
     * @return true if there is at least one session on the stack, false otherwise
     */
    static boolean hasNativeSession() {
        return !NATIVE_SESSION_STACK.get().empty()
    }
    
    /**
     * Checks if the current thread is within an active native MongoDB transaction.
     * 
     * <p>This method not only checks for the presence of a session but also
     * verifies that the session has an active transaction. This is the most
     * reliable way to determine if native transaction semantics should be used.</p>
     * 
     * <p><strong>Usage in GORM:</strong></p>
     * <ul>
     *   <li>Session selection (MongoNativeCodecSession vs MongoCodecSession)</li>
     *   <li>Persister selection (immediate vs queued execution)</li>
     *   <li>Operation routing (native vs traditional paths)</li>
     * </ul>
     * 
     * @return true if there is an active native transaction, false otherwise
     */
    static boolean isInNativeTransaction() {
        ClientSession session = getNativeSession()
        return session != null && session.hasActiveTransaction()
    }
}