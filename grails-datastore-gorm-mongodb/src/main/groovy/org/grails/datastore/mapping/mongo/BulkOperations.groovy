package org.grails.datastore.mapping.mongo

import com.mongodb.bulk.BulkWriteResult
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.DeleteOneModel
import com.mongodb.client.model.InsertOneModel
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.WriteModel
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.bson.Document
import org.grails.datastore.mapping.core.AbstractDatastore
import org.grails.datastore.mapping.dirty.checking.DirtyCheckable
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.mongo.engine.codecs.PersistentEntityCodec
import org.grails.datastore.mapping.reflect.EntityReflector

import java.util.stream.Collectors

/**
 * Provides bulk operation support for MongoDB domain classes with native transaction support.
 */
@Slf4j
@CompileStatic
class BulkOperations {
    
    static BulkWriteResult insertAll(Class domainClass, List entities) {
        if (!entities) {
            throw new IllegalArgumentException("Entities list cannot be null or empty")
        }
        
        AbstractMongoSession session = getCurrentSession(domainClass)
        PersistentEntity entity = session.mappingContext.getPersistentEntity(domainClass.name)
        MongoCollection<Document> collection = session.getCollection(entity)
        
        log.debug("Bulk insert {} entities for {}", entities.size(), domainClass.simpleName)

        List<? extends WriteModel<Document>> writes = entities
                .collect { obj ->
                    Document doc = encodeEntity(session, entity, obj)
                    new InsertOneModel<Document>(doc)
                }
        
        return executeWithSession(collection, writes)
    }
    
    static BulkWriteResult saveAll(Class domainClass, List entities) {
        if (!entities) {
            throw new IllegalArgumentException("Entities list cannot be null or empty")
        }
        
        AbstractMongoSession session = getCurrentSession(domainClass)
        PersistentEntity entity = session.mappingContext.getPersistentEntity(domainClass.name)
        MongoCollection<Document> collection = session.getCollection(entity)
        EntityReflector reflector = entity.reflector
        
        log.debug("Bulk save {} entities for {}", entities.size(), domainClass.simpleName)
        
        List<? extends WriteModel<Document>> writes = entities.collect { obj ->
            def id = reflector.getIdentifier(obj)
            Document doc = encodeEntity(session, entity, obj)
            
            if (id) {
                new UpdateOneModel<Document>(
                    new Document('_id', id),
                    new Document('$set', doc)
                )
            } else {
                new InsertOneModel<Document>(doc)
            }
        }
        
        return executeWithSession(collection, writes)
    }
    
    static BulkWriteResult deleteAll(Class domainClass, List ids) {
        if (!ids) {
            throw new IllegalArgumentException("IDs list cannot be null or empty")
        }
        
        AbstractMongoSession session = getCurrentSession(domainClass)
        PersistentEntity entity = session.mappingContext.getPersistentEntity(domainClass.name)
        MongoCollection<Document> collection = session.getCollection(entity)
        
        log.debug("Bulk delete {} entities for {}", ids.size(), domainClass.simpleName)

        List<? extends WriteModel<Document>> writes =
                ids.collect { id -> new DeleteOneModel<Document>(new Document('_id', id))}

        return executeWithSession(collection, writes)
    }
    
    private static BulkWriteResult executeWithSession(MongoCollection<Document> collection, List<? extends WriteModel<Document>> writes) {
        ClientSession nativeSession = MongoNativeTransactionContext.getNativeSession()
        
        if (nativeSession) {
            log.trace("Executing bulk write with native session")
            return collection.bulkWrite(nativeSession, writes)
        } else {
            log.trace("Executing bulk write without session")
            return collection.bulkWrite(writes)
        }
    }
    
    private static Document encodeEntity(AbstractMongoSession session, PersistentEntity entity, Object obj) {
        if (session instanceof MongoCodecSession) {
//            return ((MongoCodecSession) session).encode(obj)
            return new Document()
        } else {
            // Fallback for other session types
            throw new IllegalStateException("Unsupported session type: ${session.class.name}")
        }
    }
    
    private static AbstractMongoSession getCurrentSession(Class domainClass) {
        return  (AbstractMongoSession) AbstractDatastore.retrieveSession(MongoDatastore)
    }
}