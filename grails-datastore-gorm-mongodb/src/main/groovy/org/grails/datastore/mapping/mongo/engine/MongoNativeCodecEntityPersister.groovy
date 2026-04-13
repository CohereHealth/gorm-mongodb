package org.grails.datastore.mapping.mongo.engine

import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.ReturnDocument
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.bson.Document
import org.grails.datastore.mapping.cache.TPCacheAdapterRepository
import org.grails.datastore.mapping.core.OptimisticLockingException
import org.grails.datastore.mapping.core.SessionImplementor
import org.grails.datastore.mapping.engine.EntityAccess
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.types.ToOne
import org.grails.datastore.mapping.mongo.MongoCodecSession
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import org.grails.datastore.mapping.mongo.engine.codecs.PersistentEntityCodec
import org.grails.datastore.mapping.proxy.ProxyFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException

/**
 * Entity persister that executes all MongoDB operations immediately within a native
 * {@link ClientSession}, bypassing the pending-operations queue used by the parent
 * {@link MongoCodecEntityPersister}.
 *
 * <h3>Why a separate persister is needed</h3>
 * <p>The parent persister queues inserts, updates, and deletes as
 * {@code PendingInsert}/{@code PendingUpdate}/{@code PendingDelete} objects that are
 * executed later during {@code session.flush()}. In a native MongoDB transaction every
 * operation must be sent to the server through the same {@code ClientSession} before
 * commit, and the flush-based model does not pass the session to the driver. This
 * persister removes that indirection and calls the driver directly.</p>
 *
 * <h3>Key differences from the parent</h3>
 * <ul>
 *   <li><strong>persistEntity</strong> — executes {@code collection.insertOne(session, obj)}
 *       or {@code collection.updateOne(session, query, update)} inline instead of
 *       creating pending operation adapters. The {@code isPendingAlready} guard and
 *       {@code registerPending} call are removed because there is no queue to protect
 *       against double-scheduling; without this removal a second {@code save()} on the
 *       same object would be silently skipped.</li>
 *   <li><strong>Single-arg persistEntity</strong> — overridden to derive {@code isInsert}
 *       from whether the object already has an identifier. The parent hardcodes
 *       {@code isInsert = true}, which causes a duplicate-key error when an existing
 *       entity is saved a second time.</li>
 *   <li><strong>executeUpdate</strong> — passes the codec's {@code encodeUpdate} output
 *       directly to {@code updateOne}. The codec already produces a document containing
 *       {@code $set} with the incremented version; wrapping it in another {@code $set}
 *       or adding a separate {@code $inc} would produce an invalid update document
 *       rejected by the server.</li>
 *   <li><strong>retrieveEntity / deleteEntity / generateIdentifier</strong> — each
 *       overridden to pass the {@code ClientSession} to the corresponding driver call
 *       so that reads, deletes, and ID generation participate in the transaction.</li>
 * </ul>
 *
 * @author Puneet Behl
 * @since 6.x
 * @see MongoCodecEntityPersister
 * @see org.grails.datastore.mapping.mongo.MongoNativeCodecSession
 * @see org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
 */
@Slf4j
@CompileStatic
class MongoNativeCodecEntityPersister extends MongoCodecEntityPersister {

    MongoNativeCodecEntityPersister(MappingContext mappingContext,
                                    PersistentEntity entity,
                                    MongoCodecSession session,
                                    ApplicationEventPublisher publisher,
                                    TPCacheAdapterRepository<Object> cacheAdapterRepository) {
        super(mappingContext, entity, session, publisher, cacheAdapterRepository)
    }

    private ClientSession getNativeSession() {
        return MongoNativeTransactionContext.getNativeSession()
    }

    @Override
    protected Serializable persistEntity(PersistentEntity pe, Object obj) {
        return persistEntity(pe, obj, getObjectIdentifier(obj) == null)
    }

    @Override
    protected Serializable persistEntity(PersistentEntity entity, Object obj, boolean isInsert) {
        ProxyFactory proxyFactory = getProxyFactory()
        obj = proxyFactory.unwrap(obj)

        Serializable id = getObjectIdentifier(obj)
        SessionImplementor<Object> si = (SessionImplementor<Object>) session

        final boolean idIsNull = id == null
        boolean isUpdate = !idIsNull && !isInsert
        boolean assignedId = isAssignedId(persistentEntity)
        if (isNotUpdateForAssignedId(persistentEntity, obj, isUpdate, assignedId, si)) {
            isUpdate = false
        }
        if (isUpdate && !getSession().isDirty(obj)) {
            return id
        }

        final EntityAccess entityAccess = createEntityAccess(entity, obj)
        boolean isAssigned = isAssignedId(entity)
        if (!isAssigned && idIsNull) {
            id = generateIdentifier(entity)
            if (id != null) {
                entityAccess.setIdentifier(id)
            } else {
                throw new DataIntegrityViolationException("Failed to generate a valid identifier for entity [$obj]")
            }
        } else if (idIsNull) {
            throw new DataIntegrityViolationException("Entity [$obj] has null identifier when identifier strategy is manual assignment. Assign an appropriate identifier before persisting.")
        } else if (isAssigned && !si.isStateless(entity)) {
            isUpdate = mongoSession.contains(obj)
        }

        processAssociations(mongoSession, entity, entityAccess, obj, proxyFactory, isUpdate)

        MongoCollection collection = getMongoCollection(entity)
        ClientSession clientSession = getNativeSession()

        if (!isUpdate) {
            if (!cancelInsert(entity, entityAccess)) {
                if (clientSession) {
                    collection.insertOne(clientSession, obj)
                } else {
                    collection.insertOne(obj)
                }
                updateCaches(entity, obj, id)
                firePostInsertEvent(entity, entityAccess)
            }
        } else {
            if (!cancelUpdate(entity, entityAccess)) {
                executeUpdate(entity, obj, id, collection, entityAccess, clientSession)
                updateCaches(entity, obj, id)
                firePostUpdateEvent(entity, entityAccess)
            }
        }

        return id
    }

    @Override
    protected Object retrieveEntity(PersistentEntity pe, Serializable key) {
        Object o = getFromTPCache(pe, key)
        if (o != null) return o
        if (cancelLoad(pe, null)) return null

        MongoCollection collection = getMongoCollection(pe)
        Document idQuery = createIdQuery(key)
        ClientSession session = getNativeSession()

        o = session ?
            collection.find(session, idQuery, pe.javaClass).limit(1).first() :
            collection.find(idQuery, pe.javaClass).limit(1).first()

        if (o != null && !cancelLoad(pe, createEntityAccess(pe, o))) {
            firePostLoadEvent(pe, createEntityAccess(pe, o))
            return o
        }
        return null
    }

    @Override
    protected void deleteEntity(PersistentEntity pe, Object obj) {
        def proxyFactory = getProxyFactory()
        Serializable id
        if (proxyFactory.isProxy(obj)) {
            id = proxyFactory.getIdentifier(obj)
            obj = proxyFactory.unwrap(obj)
        } else {
            id = getObjectIdentifier(obj)
        }
        if (id == null) return

        def entityAccess = createEntityAccess(pe, obj)
        if (cancelDelete(pe, entityAccess)) return

        MongoCollection collection = getMongoCollection(pe)
        ClientSession session = getNativeSession()
        Document idQuery = createIdQuery(id)

        if (session) {
            collection.deleteOne(session, idQuery)
        } else {
            collection.deleteOne(idQuery)
        }

        mongoSession.clear(obj)
        firePostDeleteEvent(pe, entityAccess)

        for (association in pe.associations) {
            if (association.isOwningSide() && association.doesCascade(javax.persistence.CascadeType.REMOVE)
                    && !association.isEmbedded() && !(association instanceof org.grails.datastore.mapping.model.types.Basic)) {
                def v = entityAccess.getProperty(association.name)
                if (v == null) continue
                if (association instanceof ToOne) {
                    if (association.isBidirectional() && association.isCircular()) continue
                    mongoSession.delete(v)
                } else {
                    mongoSession.delete((Iterable) v)
                }
            }
        }
    }

    @Override
    Serializable generateIdentifier(final PersistentEntity persistentEntity) {
        if (hasNumericalIdentifier) {
            final String collectionName = getCollectionName(persistentEntity)
            final MongoClient client = (MongoClient) mongoSession.nativeInterface
            final MongoCollection<Document> dbCollection = client
                    .getDatabase(mongoSession.getDatabase(persistentEntity))
                    .getCollection("${collectionName}${NEXT_ID_SUFFIX}")

            ClientSession session = getNativeSession()
            def options = new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
            int attempts = 0

            while (true) {
                Document result = session ?
                    dbCollection.findOneAndUpdate(session, new Document(MONGO_ID_FIELD, collectionName), new Document(INC_OPERATOR, new Document(NEXT_ID, 1L)), options) :
                    dbCollection.findOneAndUpdate(new Document(MONGO_ID_FIELD, collectionName), new Document(INC_OPERATOR, new Document(NEXT_ID, 1L)), options)

                if (result != null) {
                    return result.getLong(NEXT_ID)
                }
                if (++attempts > 3) {
                    throw new org.grails.datastore.mapping.core.IdentityGenerationException(
                        "Unable to generate identity for [$persistentEntity.name] using findAndModify after 3 attempts")
                }
            }
        }
        return super.generateIdentifier(persistentEntity)
    }

    private void executeUpdate(PersistentEntity entity, Object obj, Serializable id,
                               MongoCollection collection, EntityAccess entityAccess, ClientSession session) {
        def updateDoc = encodeUpdate(obj, entityAccess)
        if (!updateDoc) return

        Document query = new Document("_id", id)
        if (entity.isVersioned()) {
            // encodeUpdate already incremented the version and included it in $set
            // Use the post-increment version minus 1 for the optimistic lock query
            def newVersion = entityAccess.getProperty(entity.version.name)
            def currentVersion = ((Number) newVersion).longValue() - 1
            query.append("version", currentVersion)
        }

        def result = session ?
            collection.updateOne(session, query, updateDoc) :
            collection.updateOne(query, updateDoc)

        if (entity.isVersioned() && result.matchedCount == 0) {
            throw new OptimisticLockingException(entity, obj)
        }
    }

    private Document encodeUpdate(Object obj, EntityAccess entityAccess) {
        PersistentEntityCodec codec = (PersistentEntityCodec) mongoDatastore.codecRegistry.get(obj.getClass())
        return codec.encodeUpdate(obj, entityAccess)
    }
}
