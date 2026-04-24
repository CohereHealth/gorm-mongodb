package org.grails.datastore.gorm.mongo.api

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.gorm.GormInstanceApi
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext

/**
 * Instance API implementation for MongoDB domain classes with native transaction support.
 * 
 * <h2>Purpose and Context</h2>
 * <p>This class provides instance methods for MongoDB domain objects when native transaction
 * support is enabled. It extends the standard GormInstanceApi with enhanced transaction
 * handling that automatically routes operations through native MongoDB transactions when
 * appropriate, ensuring immediate execution and proper ACID semantics.</p>
 * 
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Automatic Transaction Detection:</strong> Detects native transaction context and routes operations accordingly</li>
 *   <li><strong>Immediate Execution:</strong> Operations execute immediately within native transactions</li>
 *   <li><strong>Optimistic Locking:</strong> Proper version handling for concurrent modifications</li>
 *   <li><strong>Backward Compatibility:</strong> Falls back to standard GORM behavior when native transactions aren't active</li>
 * </ul>
 * 
 * <h2>Operation Routing Logic</h2>
 * <p>For save() and delete() operations:</p>
 * <ol>
 *   <li><strong>Native Session Active:</strong> Execute immediately within the native transaction</li>
 *   <li><strong>Native Transactions Enabled:</strong> Start a new native transaction if none exists</li>
 *   <li><strong>Standard Mode:</strong> Use traditional GORM behavior with flush-based execution</li>
 * </ol>
 * 
 * <h2>Usage Examples</h2>
 * 
 * <h3>Basic Operations in Native Transaction</h3>
 * <pre>{@code
 * Person.withTransaction { status ->
 *     def person = new Person(name: "John")
 *     person.save() // Executes immediately via native transaction
 *     
 *     person.name = "Jane"
 *     person.save() // Updates immediately with optimistic locking
 *     
 *     person.delete() // Deletes immediately
 * }
 * }</pre>
 * 
 * <h3>Mixed Transaction Contexts</h3>
 * <pre>{@code
 * // Outside transaction - uses standard GORM
 * def person = new Person(name: "Standard").save()
 * 
 * // Inside native transaction - uses immediate execution
 * Person.withTransaction { status ->
 *     person.name = "Updated"
 *     person.save() // Immediate execution
 * }
 * }</pre>
 * 
 * @param <D> the domain class type
 * @author GORM Team
 * @since 6.x
 * @see GormInstanceApi
 * @see MongoNativeTransactionSupport
 * @see MongoNativeTransactionContext
 */
@Slf4j
@CompileStatic
class MongoNativeInstanceApi<D> extends GormInstanceApi<D> implements MongoNativeTransactionSupport {

    /**
     * Constructs a new MongoNativeInstanceApi for the given domain class.
     * 
     * @param persistentClass the domain class this API serves
     * @param datastore the MongoDB datastore instance
     */
    MongoNativeInstanceApi(Class<D> persistentClass, MongoDatastore datastore) {
        super(persistentClass, datastore)
        log.debug("Created MongoNativeInstanceApi for domain class: {}", persistentClass.simpleName)
    }

    /**
     * Saves a domain instance, automatically selecting between native transaction
     * immediate execution and standard GORM flush-based execution.
     * 
     * @param instance the domain instance to save
     * @param params save parameters (validate, flush, failOnError, etc.)
     * @return the saved instance
     * @throws org.grails.datastore.mapping.validation.ValidationException if validation fails
     * @throws org.springframework.dao.OptimisticLockingFailureException if optimistic locking fails
     */
    @Override
    D save(D instance, Map params) {
        boolean hasNativeSession = MongoNativeTransactionContext.hasNativeSession()
        boolean nativeEnabled = ((MongoDatastore) datastore).isNativeTransactionsEnabled()
        
        log.debug("save() called for {}: hasNativeSession={}, nativeEnabled={}, params={}", 
                 persistentClass.simpleName, hasNativeSession, nativeEnabled, params)
        
        if (hasNativeSession || nativeEnabled) {
            try {
                log.trace("Executing save() via native transaction for {}", persistentClass.simpleName)
                return (D) executeInNativeTransaction { super.save(instance, params) }
            } catch (Exception e) {
                log.error("Native transaction save failed for {}: {}", persistentClass.simpleName, e.message, e)
                throw e
            }
        }
        
        log.trace("Executing save() via standard GORM for {}", persistentClass.simpleName)
        try {
            return (D) super.save(instance, params)
        } catch (Exception e) {
            log.error("Standard save failed for {}: {}", persistentClass.simpleName, e.message, e)
            throw e
        }
    }

    /**
     * Deletes a domain instance, automatically selecting between native transaction
     * immediate execution and standard GORM flush-based execution.
     * 
     * @param instance the domain instance to delete
     * @param params delete parameters (flush, etc.)
     * @throws org.springframework.dao.OptimisticLockingFailureException if optimistic locking fails
     */
    @Override
    void delete(D instance, Map params) {
        boolean hasNativeSession = MongoNativeTransactionContext.hasNativeSession()
        boolean nativeEnabled = ((MongoDatastore) datastore).isNativeTransactionsEnabled()
        
        log.debug("delete() called for {}: hasNativeSession={}, nativeEnabled={}, params={}", 
                 persistentClass.simpleName, hasNativeSession, nativeEnabled, params)
        
        if (hasNativeSession || nativeEnabled) {
            try {
                log.trace("Executing delete() via native transaction for {}", persistentClass.simpleName)
                executeInNativeTransaction { super.delete(instance, params) }
                return
            } catch (Exception e) {
                log.error("Native transaction delete failed for {}: {}", persistentClass.simpleName, e.message, e)
                throw e
            }
        }
        
        log.trace("Executing delete() via standard GORM for {}", persistentClass.simpleName)
        try {
            super.delete(instance, params)
        } catch (Exception e) {
            log.error("Standard delete failed for {}: {}", persistentClass.simpleName, e.message, e)
            throw e
        }
    }

    /**
     * Forces an insert operation, with native transaction support.
     * 
     * @param instance the domain instance to insert
     * @param params insert parameters
     * @return the inserted instance
     */
    @Override
    D insert(D instance, Map params) {
        boolean hasNativeSession = MongoNativeTransactionContext.hasNativeSession()
        boolean nativeEnabled = ((MongoDatastore) datastore).isNativeTransactionsEnabled()
        
        log.debug("insert() called for {}: hasNativeSession={}, nativeEnabled={}", 
                 persistentClass.simpleName, hasNativeSession, nativeEnabled)
        
        if (hasNativeSession || nativeEnabled) {
            try {
                log.trace("Executing insert() via native transaction for {}", persistentClass.simpleName)
                return (D) executeInNativeTransaction { super.insert(instance, params) }
            } catch (Exception e) {
                log.error("Native transaction insert failed for {}: {}", persistentClass.simpleName, e.message, e)
                throw e
            }
        }
        
        log.trace("Executing insert() via standard GORM for {}", persistentClass.simpleName)
        try {
            return (D) super.insert(instance, params)
        } catch (Exception e) {
            log.error("Standard insert failed for {}: {}", persistentClass.simpleName, e.message, e)
            throw e
        }
    }

    /**
     * Upgrades an existing persistence instance to a write lock with native transaction support.
     * 
     * @param instance the instance to lock
     * @return the locked instance
     */
    @Override
    D lock(D instance) {
        boolean hasNativeSession = MongoNativeTransactionContext.hasNativeSession()
        boolean nativeEnabled = ((MongoDatastore) datastore).isNativeTransactionsEnabled()
        
        log.debug("lock() called for {}: hasNativeSession={}, nativeEnabled={}", 
                 persistentClass.simpleName, hasNativeSession, nativeEnabled)
        
        if (hasNativeSession || nativeEnabled) {
            try {
                log.trace("Executing lock() via native transaction for {}", persistentClass.simpleName)
                return (D) executeInNativeTransaction { super.lock(instance) }
            } catch (Exception e) {
                log.error("Native transaction lock failed for {}: {}", persistentClass.simpleName, e.message, e)
                throw e
            }
        }
        
        log.trace("Executing lock() via standard GORM for {}", persistentClass.simpleName)
        return (D) super.lock(instance)
    }

    /**
     * Refreshes the state of the current instance with native transaction support.
     * 
     * @param instance the instance to refresh
     * @return the refreshed instance
     */
    @Override
    D refresh(D instance) {
        boolean hasNativeSession = MongoNativeTransactionContext.hasNativeSession()
        boolean nativeEnabled = ((MongoDatastore) datastore).isNativeTransactionsEnabled()
        
        log.debug("refresh() called for {}: hasNativeSession={}, nativeEnabled={}", 
                 persistentClass.simpleName, hasNativeSession, nativeEnabled)
        
        if (hasNativeSession || nativeEnabled) {
            try {
                log.trace("Executing refresh() via native transaction for {}", persistentClass.simpleName)
                return (D) executeInNativeTransaction { super.refresh(instance) }
            } catch (Exception e) {
                log.error("Native transaction refresh failed for {}: {}", persistentClass.simpleName, e.message, e)
                throw e
            }
        }
        
        log.trace("Executing refresh() via standard GORM for {}", persistentClass.simpleName)
        return (D) super.refresh(instance)
    }

    /**
     * Attaches an instance to an existing session with native transaction support.
     * 
     * @param instance the instance to attach
     * @return the attached instance
     */
    @Override
    D attach(D instance) {
        boolean hasNativeSession = MongoNativeTransactionContext.hasNativeSession()
        
        log.debug("attach() called for {}: hasNativeSession={}", 
                 persistentClass.simpleName, hasNativeSession)
        
        if (hasNativeSession) {
            try {
                log.trace("Executing attach() via native transaction for {}", persistentClass.simpleName)
                return (D) executeInNativeTransaction { super.attach(instance) }
            } catch (Exception e) {
                log.error("Native transaction attach failed for {}: {}", persistentClass.simpleName, e.message, e)
                throw e
            }
        }
        
        log.trace("Executing attach() via standard GORM for {}", persistentClass.simpleName)
        return (D) super.attach(instance)
    }

    /**
     * Discards any pending changes with native transaction awareness.
     * 
     * @param instance the instance to discard changes for
     */
    @Override
    void discard(D instance) {
        boolean hasNativeSession = MongoNativeTransactionContext.hasNativeSession()
        
        log.debug("discard() called for {}: hasNativeSession={}", 
                 persistentClass.simpleName, hasNativeSession)
        
        if (hasNativeSession) {
            log.warn("discard() called within native transaction for {} - changes may have already been committed", 
                    persistentClass.simpleName)
        }
        
        log.trace("Executing discard() for {}", persistentClass.simpleName)
        super.discard(instance)
    }

    /**
     * Executes a closure within the appropriate transaction context.
     *
     * @param closure the closure to execute
     * @return the result of the closure execution
     */
    private <T> T executeInNativeTransaction(Closure<T> closure) {
        // Check if a native session is already active
        if (MongoNativeTransactionContext.hasNativeSession()) {
            log.trace("Executing within existing native session for {}", persistentClass.simpleName)
            return closure.call()
        }

        // Try to get current session if one exists
        def session = org.grails.datastore.mapping.core.DatastoreUtils.getSession(datastore, false)
        if (session != null && session.hasTransaction()) {
            log.trace("Executing within existing transaction for {}", persistentClass.simpleName)
            return closure.call()
        }

        // Start a new native transaction
        log.trace("Starting new native transaction for {}", persistentClass.simpleName)
        return (T) withNativeTransaction(closure)
    }
}