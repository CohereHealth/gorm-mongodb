package org.grails.datastore.gorm.mongo.api

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.gorm.finders.FinderMethod
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import org.springframework.transaction.PlatformTransactionManager

/**
 * Static API implementation for MongoDB domain classes with native transaction support.
 *
 * <p>This class provides static methods for MongoDB domain classes when native transaction
 * support is enabled. It extends the standard MongoStaticApi with enhanced transaction
 * handling that can automatically choose between native MongoDB transactions and traditional
 * Spring-managed transactions based on configuration and context.</p>
 *
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Automatic Transaction Selection:</strong> Chooses native vs Spring transactions based on configuration</li>
 *   <li><strong>Nested Transaction Support:</strong> Properly handles nested transaction scenarios</li>
 *   <li><strong>Native Transaction Integration:</strong> Seamless integration with MongoDB 4.0+ native transactions</li>
 *   <li><strong>Backward Compatibility:</strong> Falls back to Spring transactions when native transactions are disabled</li>
 * </ul>
 *
 * <h2>Transaction Selection Logic</h2>
 * <p>The transaction selection follows this priority order:</p>
 * <ol>
 *   <li><strong>Existing Native Session:</strong> If already in a native transaction, reuse the session</li>
 *   <li><strong>Native Transactions Enabled:</strong> If configured, start a new native transaction</li>
 *   <li><strong>Spring Transactions:</strong> Fall back to traditional Spring transaction management</li>
 * </ol>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Basic Transaction Usage</h3>
 * <pre>{@code
 * // Automatically selects appropriate transaction type
 * Person.withTransaction { status ->
 *     def person = new Person(name: "John").save()
 *     return person
 * }
 * }</pre>
 *
 * <h3>Nested Transactions</h3>
 * <pre>{@code
 * Person.withTransaction { outerStatus ->
 *     def person1 = new Person(name: "Outer").save()
 *
 *     // Inner transaction reuses same native session
 *     Person.withTransaction { innerStatus ->
 *         def person2 = new Person(name: "Inner").save()
 *     }
 * }
 * }</pre>
 *
 * <h3>Explicit Native Transactions</h3>
 * <pre>{@code
 * // Force native transaction usage
 * Person.withNativeTransaction { session ->
 *     def person = new Person(name: "Native").save()
 *     return person
 * }
 * }</pre>
 *
 * <h2>Configuration</h2>
 * <p>Native transaction behavior is controlled by:</p>
 * <ul>
 *   <li>{@code MongoDatastore.isNativeTransactionsEnabled()}</li>
 *   <li>MongoDB server version (4.0+ required for native transactions)</li>
 *   <li>MongoDB deployment type (replica set or sharded cluster required)</li>
 * </ul>
 *
 * <h2>Integration Points</h2>
 * <p>This API integrates with:</p>
 * <ul>
 *   <li>{@link org.grails.datastore.gorm.mongo.api.MongoNativeTransactionSupport} - Common transaction functionality</li>
 *   <li>{@link org.grails.datastore.mapping.mongo.MongoNativeTransactionContext} - Thread-local session management</li>
 *   <li>{@link org.grails.datastore.mapping.mongo.MongoNativeCodecSession} - Native session implementation</li>
 *   <li>{@link org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister} - Immediate execution persister</li>
 * </ul>
 *
 * @param <D> the domain class type
 * @author Puneet Behl
 * @since 6.x
 * @see org.grails.datastore.gorm.mongo.api.MongoStaticApi
 * @see org.grails.datastore.gorm.mongo.api.MongoNativeTransactionSupport
 * @see org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
 */
@Slf4j
@CompileStatic
class MongoNativeStaticApi<D> extends MongoStaticApi<D> implements MongoNativeTransactionSupport<D> {

    /**
     * Constructs a new MongoNativeStaticApi for the given domain class.
     *
     * @param persistentClass the domain class this API serves
     * @param datastore the MongoDB datastore instance
     * @param finders list of available finder methods
     * @param transactionManager the Spring transaction manager
     */
    MongoNativeStaticApi(Class<D> persistentClass,
                         MongoDatastore datastore,
                         List<FinderMethod> finders,
                         PlatformTransactionManager transactionManager) {

        super(persistentClass, datastore, finders, transactionManager)
        log.debug("Created MongoNativeStaticApi for domain class: {}", persistentClass.simpleName)
    }

    /**
     * Executes a closure within a transaction, automatically selecting between native
     * MongoDB transactions and Spring-managed transactions based on configuration and context.
     * 
     * <h3>Transaction Selection Logic:</h3>
     * <ol>
     *   <li>If already in a native transaction context, reuse the existing session</li>
     *   <li>If native transactions are enabled, start a new native transaction</li>
     *   <li>Otherwise, fall back to Spring transaction management</li>
     * </ol>
     * 
     * <h3>Nested Transaction Behavior:</h3>
     * <p>When called within an existing native transaction, the same MongoDB ClientSession
     * is reused, ensuring all operations are part of the same atomic transaction.</p>
     * 
     * @param callable the closure to execute within the transaction
     * @return the result of the closure execution
     * @throws RuntimeException if the transaction fails or the closure throws an exception
     */
    @Override
    D withTransaction(Closure callable) {
        boolean hasExistingNativeSession = MongoNativeTransactionContext.hasNativeSession()
        boolean nativeTransactionsEnabled = ((MongoDatastore) datastore).isNativeTransactionsEnabled()
        
        log.debug("withTransaction called for {}: existingNativeSession={}, nativeEnabled={}", 
                 persistentClass.simpleName, hasExistingNativeSession, nativeTransactionsEnabled)
        
        // If already in native transaction, stay native
        if (hasExistingNativeSession) {
            log.trace("Reusing existing native transaction session for {}", persistentClass.simpleName)
            return (D) callable.call(getCurrentNativeSession())
        }

        if (nativeTransactionsEnabled) {
            try {
                log.debug("Starting new native transaction for {}", persistentClass.simpleName)
                return (D) withNativeTransaction(callable)
            } catch (Exception e) {
                log.error("Native transaction failed for {}: {}", persistentClass.simpleName, e.message, e)
                throw e
            }
        } else {
            // Check if native transactions were expected but not enabled
            if (hasExistingNativeSession && !nativeTransactionsEnabled) {
                log.warn("Native session exists but native transactions are disabled for {}. " +
                        "This may indicate a configuration mismatch.", persistentClass.simpleName)
            }
            
            log.debug("Using Spring transaction management for {}", persistentClass.simpleName)
            try {
                return (D) super.withTransaction(callable)
            } catch (Exception e) {
                log.error("Spring transaction failed for {}: {}", persistentClass.simpleName, e.message, e)
                throw e
            }
        }
    }
}