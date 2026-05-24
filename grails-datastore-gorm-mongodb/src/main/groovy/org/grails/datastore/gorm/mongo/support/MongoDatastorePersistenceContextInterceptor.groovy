package org.grails.datastore.gorm.mongo.support

import grails.persistence.support.PersistenceContextInterceptor
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.gorm.support.AbstractDatastorePersistenceContextInterceptor
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext

import javax.persistence.FlushModeType

/**
 * MongoDB-specific persistence context interceptor that considers native transaction support.
 */
@Slf4j
@CompileStatic
class MongoDatastorePersistenceContextInterceptor extends AbstractDatastorePersistenceContextInterceptor implements PersistenceContextInterceptor {

    MongoDatastorePersistenceContextInterceptor(MongoDatastore datastore) {
        super(datastore)
    }

    @Override
    void flush() {
        Session session = getSession()
        
        if (MongoNativeTransactionContext.isInNativeTransaction()) {
            log.debug("Skipping flush in native transaction - operations execute immediately")
            return
        }
        
        if (session.hasTransaction()) {
            session.flush()
        }
    }

    @Override
    void setReadOnly() {
        if (MongoNativeTransactionContext.isInNativeTransaction()) {
            log.debug("Native transaction active - read-only mode not applicable")
            return
        }
        
        getSession().setFlushMode(FlushModeType.COMMIT)
    }

    @Override
    void setReadWrite() {
        if (MongoNativeTransactionContext.isInNativeTransaction()) {
            log.debug("Native transaction active - flush mode managed by transaction")
            return
        }
        
        getSession().setFlushMode(FlushModeType.AUTO)
    }
}