package org.grails.datastore.mapping.mongo.engine

import com.mongodb.client.ClientSession
import com.mongodb.client.MongoCollection
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.bson.Document
import org.grails.datastore.mapping.cache.TPCacheAdapterRepository
import org.grails.datastore.mapping.core.OptimisticLockingException
import org.grails.datastore.mapping.engine.EntityAccess
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.mongo.MongoCodecSession
import org.grails.datastore.mapping.mongo.MongoTransactionObject
import org.grails.datastore.mapping.mongo.engine.codecs.PersistentEntityCodec
import org.grails.datastore.mapping.transactions.Transaction
import org.springframework.context.ApplicationEventPublisher

/**
 * Entity persister for MongoDB native transactions that executes operations immediately
 * while maintaining optimistic locking support.
 *
 * <p>This persister bypasses the standard flush mechanism and executes database operations
 * immediately when called, making it suitable for native MongoDB transactions where
 * operations need to be executed within the transaction boundary.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Immediate execution of insert/update operations</li>
 *   <li>Full optimistic locking support with version checking</li>
 *   <li>Native MongoDB transaction integration</li>
 *   <li>Automatic session management for transactional operations</li>
 * </ul>
 *
 * @author Puneet Behl
 * @since 6.x
 */
@Slf4j
@CompileStatic
class MongoNativeCodecEntityPersister extends MongoCodecEntityPersister {

    /**
     * Constructs a new MongoNativeCodecEntityPersister.
     *
     * @param mappingContext the mapping context
     * @param entity the persistent entity
     * @param session the MongoDB codec session
     * @param publisher the application event publisher
     * @param cacheAdapterRepository the cache adapter repository
     */
    MongoNativeCodecEntityPersister(MappingContext mappingContext,
                                    PersistentEntity entity,
                                    MongoCodecSession session,
                                    ApplicationEventPublisher publisher,
                                    TPCacheAdapterRepository<Object> cacheAdapterRepository) {
        super(mappingContext, entity, session, publisher, cacheAdapterRepository)
        log.debug("Created MongoNativeCodecEntityPersister for entity: {}", entity.name)
    }

    /**
     * Persists an entity immediately when native transactions are enabled.
     *
     * <p>Unlike the standard persister, this method executes the database operation
     * immediately rather than adding it to a pending operations queue.</p>
     *
     * @param entity the persistent entity definition
     * @param obj the entity instance to persist
     * @param isInsert true if this is an insert operation, false for update
     * @return the entity identifier
     */
    @Override
    protected Serializable persistEntity(PersistentEntity entity, Object obj, boolean isInsert) {
        log.debug("Persisting entity {} with immediate execution, isInsert: {}", entity.name, isInsert)

        Serializable id = super.persistEntity(entity, obj, isInsert)

        // Always execute immediately since this persister is only used for native transactions
        MongoCollection collection = getMongoCollection(entity)
        if (!isInsert) {
            log.debug("Executing immediate update for entity {} with id: {}", entity.name, id)
            executeUpdate(entity, obj, id, collection)
        } else {
            log.debug("Executing immediate insert for entity {} with id: {}", entity.name, id)
            executeInsert(entity, obj, id, collection)
        }

        return id
    }

    /**
     * Executes an insert operation immediately.
     *
     * @param entity the persistent entity definition
     * @param obj the entity instance to insert
     * @param id the entity identifier
     * @param collection the MongoDB collection
     */
    private void executeInsert(PersistentEntity entity, Object obj, Serializable id, MongoCollection collection) {
        Transaction tx = mongoSession.getTransaction()
        if (tx instanceof MongoTransactionObject) {
            ClientSession session = (ClientSession) mongoSession.getTransaction()?.nativeTransaction

            if (session instanceof ClientSession) {
                log.debug("Executing transactional insert for entity {} with session", entity.name)
                collection.insertOne(session, obj)
            }
        } else {
            log.debug("Executing non-transactional insert for entity {}", entity.name)
            collection.insertOne(obj)
        }

        firePostInsertEvent(entity, createEntityAccess(entity, obj))
    }

    /**
     * Executes an update operation immediately, with optimistic locking support if enabled.
     *
     * @param entity the persistent entity definition
     * @param obj the entity instance to update
     * @param id the entity identifier
     * @param collection the MongoDB collection
     */
    private void executeUpdate(PersistentEntity entity, Object obj, Serializable id, MongoCollection collection) {
        EntityAccess entityAccess = createEntityAccess(entity, obj)
        ClientSession session = (ClientSession) mongoSession.getTransaction()?.nativeTransaction

        if (entity.isVersioned()) {
            log.debug("Executing versioned update for entity {} with id: {}", entity.name, id)
            executeVersionedUpdate(entity, obj, id, collection, entityAccess, session)
        } else {
            log.debug("Executing simple update for entity {} with id: {}", entity.name, id)
            executeSimpleUpdate(entity, obj, id, collection, entityAccess, session)
        }

        firePostUpdateEvent(entity, entityAccess)
    }

    /**
     * Executes a versioned update with optimistic locking.
     *
     * <p>This method includes the current version in the query to ensure the document
     * hasn't been modified by another process. If no document matches (matchedCount == 0),
     * an OptimisticLockingException is thrown.</p>
     *
     * @param entity the persistent entity definition
     * @param obj the entity instance to update
     * @param id the entity identifier
     * @param collection the MongoDB collection
     * @param entityAccess the entity access helper
     * @param session the MongoDB client session (may be null)
     */
    private void executeVersionedUpdate(PersistentEntity entity, Object obj, Serializable id, MongoCollection collection, EntityAccess entityAccess, ClientSession session) {
        def currentVersion = entityAccess.getProperty(entity.version.name)
        def updateDoc = encodeUpdate(obj, entityAccess)

        log.debug("Executing versioned update for entity {} with version: {}", entity.name, currentVersion)

        if (updateDoc) {
            def query = new Document("_id", id).append("version", currentVersion)
            def update = new Document("\$set", updateDoc).append("\$inc", new Document("version", 1))

            def result = session ?
                    collection.updateOne(session, query, update) :
                    collection.updateOne(query, update)

            log.debug("Versioned update result - matched: {}, modified: {}", result.matchedCount, result.modifiedCount)

            if (result.matchedCount == 0) {
                log.warn("Optimistic locking failure for entity {} with id: {} and version: {}", entity.name, id, currentVersion)
                throw new OptimisticLockingException(entity, obj)
            }

            // Update version in memory
            entityAccess.setProperty(entity.version.name, ((Integer) currentVersion) + 1)
            log.debug("Updated entity {} version to: {}", entity.name, ((Integer) currentVersion) + 1)
        }
    }

    /**
     * Executes a simple update without version checking.
     *
     * @param entity the persistent entity definition
     * @param obj the entity instance to update
     * @param id the entity identifier
     * @param collection the MongoDB collection
     * @param entityAccess the entity access helper
     * @param session the MongoDB client session (may be null)
     */
    private void executeSimpleUpdate(PersistentEntity entity, Object obj, Serializable id, MongoCollection collection, EntityAccess entityAccess, ClientSession session) {
        def updateDoc = encodeUpdate(obj, entityAccess)

        if (updateDoc) {
            def query = new Document("_id", id)
            def update = new Document("\$set", updateDoc)

            if (session) {
                log.debug("Executing transactional simple update for entity {} with session", entity.name)
                collection.updateOne(session, query, update)
            } else {
                log.debug("Executing non-transactional simple update for entity {}", entity.name)
                collection.updateOne(query, update)
            }
        }
    }

    /**
     * Encodes the entity changes into a MongoDB update document.
     *
     * @param obj the entity instance
     * @param entityAccess the entity access helper
     * @return the MongoDB update document containing only changed fields
     */
    private Document encodeUpdate(Object obj, EntityAccess entityAccess) {
        PersistentEntityCodec codec = (PersistentEntityCodec) mongoDatastore.codecRegistry.get(obj.getClass())
        Document updateDoc = codec.encodeUpdate(obj, entityAccess)

        if (log.isTraceEnabled()) {
            log.trace("Encoded update document for {}: {}", obj.getClass().simpleName, updateDoc)
        }

        return updateDoc
    }
}