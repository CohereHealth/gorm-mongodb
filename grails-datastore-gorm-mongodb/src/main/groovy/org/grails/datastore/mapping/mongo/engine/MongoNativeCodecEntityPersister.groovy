package org.grails.datastore.mapping.mongo.engine

import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.DeleteOneModel
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.InsertOneModel
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
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
                if (log.isTraceEnabled()) {
                    log.trace("Inserting {} [id={}] with ClientSession={}", entity.name, id, clientSession != null)
                }
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
                if (log.isTraceEnabled()) {
                    log.trace("Updating {} [id={}] with ClientSession={}", entity.name, id, clientSession != null)
                }
                executeUpdate(entity, obj, id, collection, entityAccess, clientSession)
                updateCaches(entity, obj, id)
                firePostUpdateEvent(entity, entityAccess)
            }
        }

        return id
    }

    @Override
    protected List<Serializable> persistEntities(PersistentEntity pe, @SuppressWarnings("rawtypes") Iterable objs) {
        NativeBulkWriter writer = new NativeBulkWriter(getMongoCollection(pe), getNativeSession())
        ProxyFactory proxyFactory = getProxyFactory()
        SessionImplementor<Object> si = (SessionImplementor<Object>) session

        List<Serializable> ids = []
        // entity access + metadata needed for post-write events/caching
        List<Object[]> postOps = [] // [obj, entityAccess, id, isInsert(boolean)]

        for (raw in objs) {
            Object obj = proxyFactory.unwrap(raw)
            Serializable id = getObjectIdentifier(obj)
            boolean idIsNull = id == null
            boolean isUpdate = !idIsNull
            boolean assignedId = isAssignedId(pe)

            if (isNotUpdateForAssignedId(pe, obj, isUpdate, assignedId, si)) {
                isUpdate = false
            }
            if (isUpdate && !getSession().isDirty(obj)) {
                ids << id
                continue
            }

            EntityAccess entityAccess = createEntityAccess(pe, obj)
            if (!assignedId && idIsNull) {
                id = generateIdentifier(pe)
                if (id != null) {
                    entityAccess.setIdentifier(id)
                } else {
                    throw new DataIntegrityViolationException("Failed to generate identifier for [$obj]")
                }
            } else if (idIsNull) {
                throw new DataIntegrityViolationException("Entity [$obj] has null identifier with manual assignment strategy")
            } else if (assignedId && !si.isStateless(pe)) {
                isUpdate = mongoSession.contains(obj)
            }

            processAssociations(mongoSession, pe, entityAccess, obj, proxyFactory, isUpdate)
            ids << id

            if (!isUpdate) {
                if (!cancelInsert(pe, entityAccess)) {
                    writer.add(new InsertOneModel(obj))
                    postOps << ([obj, entityAccess, id, true] as Object[])
                }
            } else {
                if (!cancelUpdate(pe, entityAccess)) {
                    def updateDoc = encodeUpdate(obj, entityAccess)
                    if (updateDoc) {
                        Document query = createVersionedIdQuery(pe, id, entityAccess)
                        writer.add(new UpdateOneModel(query, updateDoc, new UpdateOptions().upsert(false)))
                        postOps << ([obj, entityAccess, id, false] as Object[])
                    }
                }
            }
        }

        if (writer.hasWrites()) {
            writer.execute()

            for (op in postOps) {
                updateCaches(pe, op[0], (Serializable) op[2])
                if ((boolean) op[3]) {
                    firePostInsertEvent(pe, (EntityAccess) op[1])
                } else {
                    firePostUpdateEvent(pe, (EntityAccess) op[1])
                }
            }
        }

        return ids
    }

    @Override
    protected void deleteEntities(PersistentEntity pe, @SuppressWarnings("rawtypes") Iterable objects) {
        NativeBulkWriter writer = new NativeBulkWriter(getMongoCollection(pe), getNativeSession())
        ProxyFactory proxyFactory = getProxyFactory()
        List<Object[]> postOps = [] // [obj, entityAccess]

        for (raw in objects) {
            Object obj
            Serializable id
            if (proxyFactory.isProxy(raw)) {
                id = proxyFactory.getIdentifier(raw)
                obj = proxyFactory.unwrap(raw)
            } else {
                obj = raw
                id = getObjectIdentifier(obj)
            }
            if (id == null) continue

            EntityAccess entityAccess = createEntityAccess(pe, obj)
            if (cancelDelete(pe, entityAccess)) continue

            writer.add(new DeleteOneModel(createIdQuery(id)))
            postOps << ([obj, entityAccess] as Object[])
        }

        if (writer.hasWrites()) {
            writer.execute()

            for (op in postOps) {
                mongoSession.clear(op[0])
                firePostDeleteEvent(pe, (EntityAccess) op[1])
            }
        }
    }

    // ------------------------------------------------------------------
    // Single-entity read / delete (immediate execution with ClientSession)
    // ------------------------------------------------------------------

    @Override
    protected Object retrieveEntity(PersistentEntity pe, Serializable key) {
        Object o = getFromTPCache(pe, key)
        if (o != null) {
            if (log.isTraceEnabled()) {
                log.trace("Cache hit for {} [id={}]", pe.name, key)
            }
            return o
        }
        if (cancelLoad(pe, null)) return null

        MongoCollection collection = getMongoCollection(pe)
        Document idQuery = createIdQuery(key)
        ClientSession session = getNativeSession()

        if (log.isTraceEnabled()) {
            log.trace("Retrieving {} [id={}] with ClientSession={}", pe.name, key, session != null)
        }

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

        if (log.isTraceEnabled()) {
            log.trace("Deleting {} [id={}] with ClientSession={}", pe.name, id, session != null)
        }

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

    private Document createVersionedIdQuery(PersistentEntity pe, Serializable id, EntityAccess entityAccess) {
        Document query = new Document("_id", id)
        if (pe.isVersioned()) {
            query.append("version", entityAccess.getProperty(pe.version.name))
        }
        return query
    }

    private void executeUpdate(PersistentEntity entity, Object obj, Serializable id,
                               MongoCollection collection, EntityAccess entityAccess, ClientSession session) {
        // Build the version query BEFORE encodeUpdate, because encodeUpdate
        // increments the in-memory version via incrementEntityVersion(access).
        boolean versioned = entity.isVersioned()
        Object originalVersion = versioned ? entityAccess.getProperty(entity.version.name) : null
        Document query = createVersionedIdQuery(entity, id, entityAccess)

        def updateDoc = encodeUpdate(obj, entityAccess)
        if (!updateDoc) return

        def result
        try {
            result = session ?
                collection.updateOne(session, query, updateDoc) :
                collection.updateOne(query, updateDoc)
        } catch (RuntimeException e) {
            // The write did not persist (e.g. a transaction WriteConflict/TransientTransactionError). encodeUpdate
            // already bumped the in-memory version; restore it so a transaction retry re-issues the write with the
            // persistent version rather than the optimistically-incremented one (which would never match, turning a
            // retryable transient conflict into a spurious OptimisticLockingException on the next attempt).
            if (versioned) {
                entityAccess.setProperty(entity.version.name, originalVersion)
            }
            throw e
        }

        if (versioned && result.matchedCount == 0) {
            // Genuine version mismatch: the write did not apply, so undo the in-memory increment before signalling.
            entityAccess.setProperty(entity.version.name, originalVersion)
            if (log.isWarnEnabled()) {
                log.warn("Optimistic locking failure for {} [id={}]: version mismatch", entity.name, id)
            }
            throw new OptimisticLockingException(entity, obj)
        }
    }

    private Document encodeUpdate(Object obj, EntityAccess entityAccess) {
        PersistentEntityCodec codec = (PersistentEntityCodec) mongoDatastore.codecRegistry.get(obj.getClass())
        return codec.encodeUpdate(obj, entityAccess)
    }
}
