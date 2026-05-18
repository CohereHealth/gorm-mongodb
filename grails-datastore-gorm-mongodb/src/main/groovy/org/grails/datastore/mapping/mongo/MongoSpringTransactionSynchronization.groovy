package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@CompileStatic
class MongoSpringTransactionSynchronization implements TransactionSynchronization {
    
    private final ClientSession clientSession
    
    MongoSpringTransactionSynchronization(ClientSession clientSession) {
        this.clientSession = clientSession
    }
    
    @Override
    void afterCompletion(int status) {
        try {
            if (status == STATUS_COMMITTED) {
                if (clientSession.hasActiveTransaction()) {
                    clientSession.commitTransaction()
                }
            } else {
                if (clientSession.hasActiveTransaction()) {
                    clientSession.abortTransaction()
                }
            }
        } finally {
            MongoNativeTransactionContext.popNativeSession()
            clientSession.close()
        }
    }
    
    static void registerSynchronization(ClientSession session) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new MongoSpringTransactionSynchronization(session)
            )
        }
    }
}