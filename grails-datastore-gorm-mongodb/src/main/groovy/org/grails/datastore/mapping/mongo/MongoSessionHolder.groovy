package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.transactions.SessionHolder
import org.grails.datastore.mapping.transactions.Transaction

/**
 * MongoDB-specific session holder that manages both GORM Session and MongoDB ClientSession.
 * Extends SessionHolder to remain compatible with DatastoreUtils and the core transaction infrastructure.
 */
@CompileStatic
class MongoSessionHolder extends SessionHolder {
    
    private ClientSession clientSession
    private volatile Transaction nativeTransaction

    MongoSessionHolder(Session session, ClientSession clientSession) {
        super(session)
        this.clientSession = clientSession
    }
    
    ClientSession getClientSession() {
        return clientSession
    }
    
    @Override
    Transaction getTransaction() {
        return nativeTransaction != null ? nativeTransaction : super.getTransaction()
    }
    
    void setTransaction(Transaction transaction) {
        this.nativeTransaction = transaction
    }
    
    boolean hasNativeTransaction() {
        return clientSession != null && clientSession.hasActiveTransaction()
    }
}
