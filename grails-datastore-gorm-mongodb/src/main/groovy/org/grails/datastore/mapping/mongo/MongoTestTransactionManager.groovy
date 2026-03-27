package org.grails.datastore.mapping.mongo


import groovy.transform.CompileStatic
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionStatus

@CompileStatic
class MongoTestTransactionManager extends MongoTransactionManager {
    
    private boolean rollbackOnly = false
    
    MongoTestTransactionManager(MongoDatastore datastore) {
        super(datastore)
    }
    
    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        if (rollbackOnly) {
            doRollback(status)
        } else {
            final Object transaction = status.getTransaction()
            if (transaction instanceof MongoNativeTransactionObject) {
                ((MongoNativeTransactionObject) transaction).clientSession.commitTransaction()
            } else {
                super.doCommit(status)
            }
        }
    }
    
    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        Object transaction = status.getTransaction()
        if (transaction instanceof MongoNativeTransactionObject) {
            ((MongoNativeTransactionObject) transaction).clientSession.abortTransaction()
        } else {
            super.doRollback(status)
        }
    }
    
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        // Always call parent to initialize ClientSession
        super.doBegin(transaction, definition)
    }
    
    void setRollbackOnly() {
        this.rollbackOnly = true
    }
}