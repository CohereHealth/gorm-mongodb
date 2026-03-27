package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.transactions.SessionHolder
import org.grails.datastore.mapping.transactions.Transaction
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * PlatformTransactionManager implementation for MongoDB native transactions.
 * Bridges Spring transaction management with MongoDB ClientSession.
 */
@Slf4j
@CompileStatic
class MongoTransactionManager extends AbstractPlatformTransactionManager {
    
    private final MongoDatastore datastore

    MongoTransactionManager(MongoDatastore datastore) {
        this.datastore = datastore
    }
    
    @Override
    protected Object doGetTransaction() throws TransactionException {
        return new MongoNativeTransactionObject(datastore)
    }
    
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        MongoNativeTransactionObject txObject = (MongoNativeTransactionObject) transaction
        
        // Create session (will be MongoNativeCodecSession if native transactions enabled)
        Session session = datastore.connect()
        SessionHolder sessionHolder = new SessionHolder(session)
        txObject.setSessionHolder(sessionHolder)
        
        // Begin transaction on the session
        Transaction tx = session.beginTransaction(definition)

        // Extract ClientSession from native transaction
        if (tx instanceof MongoTransactionObject) {
            ClientSession clientSession = ((MongoTransactionObject) tx).getNativeTransaction()
            txObject.setClientSession(clientSession)
            log.debug("Started MongoDB native transaction with ClientSession")
        } else {
            log.debug("Started standard MongoDB transaction")
        }
        
        // Bind session to TransactionSynchronizationManager
        TransactionSynchronizationManager.bindResource(datastore, sessionHolder)
    }
    
    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        MongoNativeTransactionObject txObject = (MongoNativeTransactionObject) status.transaction
        Transaction tx = txObject.sessionHolder?.transaction
        if (tx) {
            tx.commit()
            log.debug("Committed MongoDB transaction")
        } else {
            log.debug("No transaction to commit")
        }
    }
    
    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        MongoNativeTransactionObject txObject = (MongoNativeTransactionObject) status.transaction
        Transaction tx = txObject.sessionHolder?.transaction
        if (tx) {
            tx.rollback()
            log.debug("Rolled back MongoDB transaction")
        } else {
            log.debug("No transaction to rollback")
        }
    }
    
    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        MongoNativeTransactionObject txObject = (MongoNativeTransactionObject) transaction
        
        // Unbind session from TransactionSynchronizationManager
        TransactionSynchronizationManager.unbindResourceIfPossible(datastore)
        
        // Close session
        txObject.sessionHolder?.session?.disconnect()
        txObject.clientSession?.close()
    }
    
    static class MongoNativeTransactionObject {
        ClientSession clientSession
        final MongoDatastore datastore
        SessionHolder sessionHolder
        
        MongoNativeTransactionObject(MongoDatastore datastore) {
            this.datastore = datastore
        }
        
        void setClientSession(ClientSession session) {
            this.clientSession = session
        }
        
        void setSessionHolder(SessionHolder sessionHolder) {
            this.sessionHolder = sessionHolder
        }
        
        SessionHolder getSessionHolder() {
            return sessionHolder
        }
    }
}