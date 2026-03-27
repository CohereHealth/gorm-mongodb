package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.transactions.Transaction
import org.springframework.transaction.support.ResourceHolderSupport

import java.util.concurrent.LinkedBlockingDeque

/**
 * MongoDB-specific session holder that manages both GORM Session and MongoDB ClientSession.
 * Extends SessionHolder to provide native MongoDB transaction support with thread safety.
 */
@CompileStatic
class MongoSessionHolder extends ResourceHolderSupport {
    
    private final Deque<ClientSession> clientSessions = new LinkedBlockingDeque<ClientSession>()
    private volatile Transaction transaction

    MongoSessionHolder(ClientSession clientSession) {
        if (clientSession != null) {
            clientSessions.add(clientSession)
        }
    }
    
    /**
     * Gets the current MongoDB ClientSession (most recent).
     */
    ClientSession getClientSession() {
        return clientSessions.peekLast()
    }
    
    /**
     * Adds a ClientSession to the queue.
     */
    void addClientSession(ClientSession clientSession) {
        if (clientSession != null) {
            clientSessions.add(clientSession)
        }
    }
    
    /**
     * Removes a ClientSession from the queue.
     */
    void removeClientSession(ClientSession clientSession) {
        clientSessions.remove(clientSession)
    }
    
    /**
     * Gets the current transaction, preferring native transaction if available.
     */
    Transaction getTransaction() {
        if (transaction != null) {
            return transaction
        }
        return null
    }
    
    /**
     * Sets the transaction for this holder.
     */
    void setTransaction(Transaction transaction) {
        this.transaction = transaction
    }
    
    /**
     * Checks if this holder has an active native MongoDB transaction.
     */
    boolean hasNativeTransaction() {
        ClientSession clientSession = getClientSession()
        return clientSession != null && clientSession.hasActiveTransaction()
    }
    
    /**
     * Checks if this holder is valid (has active client session).
     */
    boolean isValid() {
        ClientSession clientSession = getClientSession()
        return clientSession != null && !clientSession.hasActiveTransaction()
    }
}