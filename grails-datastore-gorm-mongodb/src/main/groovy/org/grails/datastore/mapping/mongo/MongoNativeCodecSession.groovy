package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.bson.Document
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.ToOne
import org.grails.datastore.mapping.mongo.engine.MongoCodecEntityPersister
import org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister
import org.grails.datastore.mapping.mongo.query.MongoQuery
import org.grails.datastore.mapping.query.Query
import org.grails.datastore.mapping.query.api.QueryableCriteria
import org.grails.datastore.mapping.transactions.Transaction
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.NoTransactionException

/**
 * MongoDB session implementation for native transactions that executes operations immediately
 * instead of using the traditional pending operations queue and flush mechanism.
 * 
 * <h2>Purpose and Context</h2>
 * <p>This session is specifically designed for MongoDB native transactions (MongoDB 4.0+) where
 * operations must be executed immediately within the transaction boundary rather than being
 * queued for later execution during flush. This ensures proper ACID semantics and transaction
 * isolation.</p>
 * 
 * <h2>Key Behavioral Differences</h2>
 * <ul>
 *   <li><strong>Immediate Execution:</strong> All CRUD operations execute immediately when called,
 *       not when flush() is invoked</li>
 *   <li><strong>No Pending Operations:</strong> Operations bypass the pending operations queue
 *       entirely</li>
 *   <li><strong>Session-Aware Collections:</strong> All MongoDB collections are automatically
 *       bound to the active ClientSession</li>
 *   <li><strong>Optimistic Locking:</strong> Version checking is handled immediately during
 *       updates via MongoNativeCodecEntityPersister</li>
 * </ul>
 * 
 * <h2>When This Session is Used</h2>
 * <p>This session is automatically selected when:</p>
 * <ul>
 *   <li>An active MongoDB native transaction (ClientSession) is detected</li>
 *   <li>The current transaction's nativeTransaction property is a ClientSession instance</li>
 *   <li>Operations are performed within Person.withTransaction{} or similar transaction blocks</li>
 * </ul>
 * 
 * <h2>Performance Implications</h2>
 * <ul>
 *   <li><strong>Pros:</strong> Immediate execution ensures operations are within transaction scope,
 *       proper isolation, and immediate consistency</li>
 *   <li><strong>Cons:</strong> No bulk operation optimizations since operations execute individually</li>
 *   <li><strong>Trade-off:</strong> Transaction safety over bulk performance optimization</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // This will use MongoNativeCodecSession automatically
 * Person.withTransaction { status ->
 *     def person = new Person(name: "John")
 *     person.save() // Executes immediately, not queued
 *     
 *     person.name = "Jane"  
 *     person.save() // Executes immediately with optimistic locking
 *     
 *     // flush() is unnecessary and will log a warning
 * }
 * }</pre>
 * 
 * @author Puneet Behl
 * @since 6.x
 * @see org.grails.datastore.mapping.mongo.MongoCodecSession
 * @see org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister
 */
@Slf4j
@CompileStatic
class MongoNativeCodecSession extends MongoCodecSession {

    protected Transaction transaction;

    /**
     * Constructs a new MongoNativeCodecSession for immediate execution within native transactions.
     *
     * @param datastore the MongoDB datastore instance
     * @param mappingContext the mapping context containing entity definitions
     * @param publisher the application event publisher for GORM events
     * @param stateless whether this session should be stateless (no first-level cache)
     */
    MongoNativeCodecSession(MongoDatastore datastore, MappingContext mappingContext, ApplicationEventPublisher publisher, boolean stateless) {
        super(datastore, mappingContext, publisher, stateless)
        log.debug("Created MongoNativeCodecSession for immediate execution with native transactions")
    }

    /**
     * No-operation flush method with warning for native transaction sessions.
     * 
     * <p><strong>Important:</strong> In native transaction sessions, all operations execute
     * immediately when called (save(), delete(), etc.), making flush() unnecessary and
     * potentially misleading. This method logs a warning to help developers understand
     * the behavioral difference.</p>
     * 
     * <h3>Why flush() is unnecessary:</h3>
     * <ul>
     *   <li>Operations execute immediately via addPendingInsert/Update/Delete overrides</li>
     *   <li>No pending operations queue exists to flush</li>
     *   <li>Transaction boundaries are managed by MongoDB ClientSession</li>
     * </ul>
     * 
     * <h3>Migration guidance:</h3>
     * <p>Code using explicit flush() calls can safely remove them when using native transactions:</p>
     * <pre>{@code
     * // Before (unnecessary with native transactions)
     * person.save()
     * session.flush()
     * 
     * // After (recommended)  
     * person.save() // Already executed immediately
     * }</pre>
     */
    @Override
    void flush() {
        log.warn("flush() called on MongoNativeCodecSession - operations are executed immediately, flush is unnecessary. " +
                "Consider removing explicit flush() calls when using native transactions.")
        log.trace("flush() no-op completed for native transaction session")
    }

    /**
     * Executes bulk delete operations with native transaction session support.
     * 
     * <p>This method ensures that bulk delete operations are executed within the
     * active MongoDB transaction session, maintaining ACID properties and proper
     * isolation levels.</p>
     *
     * @param criteria the query criteria for selecting documents to delete
     * @return the number of documents deleted, or 0 if the operation was not acknowledged
     */
    @Override
    long deleteAll(QueryableCriteria criteria) {
        final PersistentEntity entity = criteria.getPersistentEntity()
        log.debug("Executing bulk deleteAll for entity: {}", entity.name)
        
        final Document nativeQuery = buildNativeDocumentQueryFromCriteria(criteria, entity)
        final MongoCollection collection = getCollection(entity)
        
        ClientSession session = (ClientSession) getTransaction()?.nativeTransaction
        log.trace("Using ClientSession for deleteAll: {}", session != null)
        
        final DeleteResult deleteResult = session ? 
            collection.deleteMany(session, nativeQuery) : 
            collection.deleteMany(nativeQuery)
            
        long deletedCount = deleteResult.wasAcknowledged() ? deleteResult.deletedCount : 0
        log.debug("Bulk deleteAll completed for entity: {}, deleted: {} documents", entity.name, deletedCount)
        return deletedCount
    }

    /**
     * Executes bulk update operations with native transaction session support.
     * 
     * <p>This method ensures that bulk update operations are executed within the
     * active MongoDB transaction session, maintaining ACID properties. It also
     * handles association property conversion for proper document updates.</p>
     *
     * @param criteria the query criteria for selecting documents to update
     * @param properties the properties to update with their new values
     * @return the number of documents modified, or -1 if not supported by MongoDB version
     */
    @Override
    long updateAll(QueryableCriteria criteria, Map<String, Object> properties) {
        final PersistentEntity entity = criteria.persistentEntity
        log.debug("Executing bulk updateAll for entity: {} with {} properties", entity.name, properties.size())
        
        final Document nativeQuery = buildNativeDocumentQueryFromCriteria(criteria, entity)
        final MongoCollection collection = getCollection(entity)
        final updateOptions = new UpdateOptions().upsert(false)
        
        // Handle associations - convert domain objects to their identifiers
        for (Association association in entity.associations) {
            String associationName = association.name
            if (association instanceof ToOne && properties.containsKey(associationName)) {
                def value = properties.get(associationName)
                if (value != null) {
                    def identifier = association.associatedEntity.reflector.getIdentifier(value)
                    properties.put(associationName, identifier)
                    log.trace("Converted association {} to identifier: {}", associationName, identifier)
                }
            }
        }
        
        ClientSession session = (ClientSession) getTransaction()?.nativeTransaction
        log.trace("Using ClientSession for updateAll: {}", session != null)
        
        final UpdateResult updateResult = session ? 
            collection.updateMany(session, nativeQuery, new Document(MONGO_SET_OPERATOR, properties), updateOptions) :
            collection.updateMany(nativeQuery, new Document(MONGO_SET_OPERATOR, properties), updateOptions)
            
        if (updateResult.wasAcknowledged()) {
            try {
                long modifiedCount = updateResult.modifiedCount
                log.debug("Bulk updateAll completed for entity: {}, modified: {} documents", entity.name, modifiedCount)
                return modifiedCount
            } catch (UnsupportedOperationException e) {
                log.debug("modifiedCount not supported by MongoDB version, returning -1")
                return -1
            }
        }
        log.debug("Bulk updateAll not acknowledged for entity: {}", entity.name)
        return 0
    }

    /**
     * Returns the MongoDB collection for the given entity.
     * 
     * <p>Note: In native transaction sessions, the ClientSession is passed directly
     * to collection methods rather than binding it to the collection itself.</p>
     *
     * @param entity the persistent entity for which to get the collection
     * @return MongoDB collection for the entity
     */
    @Override
    MongoCollection getCollection(PersistentEntity entity) {
        return super.getCollection(entity)
    }

    @Override
    protected Transaction beginTransactionInternal() {
        log.debug("Beginning native transaction, native transactions enabled: {}", mongoDatastore.nativeTransactionsEnabled)

        // Check if already in native transaction context (for nested calls)
        if (MongoNativeTransactionContext.hasNativeSession()) {
            log.debug("Reusing existing native session for nested transaction")
            final ClientSession nativeSession = MongoNativeTransactionContext.getNativeSession()
            return new MongoTransactionObject(new MongoSessionHolder(nativeSession))
        }

        if (mongoDatastore.nativeTransactionsEnabled) {
            log.debug("Creating new native MongoDB transaction")
            final ClientSession clientSession = getNativeInterface().startSession()
            MongoTransactionObject tx = new MongoTransactionObject(new MongoSessionHolder(clientSession))
            MongoNativeTransactionContext.pushNativeSession(tx.getNativeTransaction())
            return tx
        } else {
            log.debug("Creating standard datastore transaction")
            return super.beginTransactionInternal()
        }
    }

    /**
     * Builds a native MongoDB query document from GORM criteria.
     * 
     * <p>This helper method converts GORM QueryableCriteria into native MongoDB
     * query documents that can be used directly with MongoDB driver operations.</p>
     *
     * @param criteria the GORM query criteria to convert
     * @param entity the persistent entity being queried
     * @return MongoDB Document representing the query
     */
    private Document buildNativeDocumentQueryFromCriteria(QueryableCriteria criteria, PersistentEntity entity) {
        log.trace("Building native query for entity: {} with {} criteria", entity.name, criteria.criteria.size())
        
        def mongoQuery = new MongoQuery(this, entity)
        for (Query.Criterion c in criteria.criteria) {
            mongoQuery.add(c)
        }
        
        Document queryDoc = mongoQuery.mongoQuery
        if (log.isTraceEnabled()) {
            log.trace("Built native query document: {}", queryDoc.toJson())
        }
        
        return queryDoc
    }

    @Override
    protected MongoCodecEntityPersister createPersister(Class cls, MappingContext mappingContext) {
        return new MongoNativeCodecEntityPersister(mappingContext, mappingContext.getPersistentEntity(cls.name), this, publisher, cacheAdapterRepository)
    }
    
    /**
     * Returns the current transaction, including native transactions from context.
     * Prevents NoTransactionException when native transaction is active.
     */
    @Override
    Transaction getTransaction() throws NoTransactionException {
        // First check if we have a regular transaction
        if (this.transaction != null) {
            return transaction
        }
        
        // Check if we're in a native transaction context
        if (MongoNativeTransactionContext.hasNativeSession()) {
            ClientSession nativeSession = MongoNativeTransactionContext.getNativeSession()
            return new MongoTransactionObject(new MongoSessionHolder(nativeSession))
        }
        
        // No transaction available
        throw new NoTransactionException("No transaction is currently active")
    }
}