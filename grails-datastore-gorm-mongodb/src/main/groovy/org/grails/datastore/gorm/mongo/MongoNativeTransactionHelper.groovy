package org.grails.datastore.gorm.mongo

import com.mongodb.client.ClientSession
import groovy.transform.CompileStatic
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext

@CompileStatic
class MongoNativeTransactionHelper {
    
    /**
     * Get the current native MongoDB ClientSession if available
     * @return ClientSession or null if not in native transaction
     */
    static ClientSession getCurrentNativeSession() {
        return MongoNativeTransactionContext.getNativeSession()
    }
    
    /**
     * Check if currently executing within a native MongoDB transaction
     * @return true if in active native transaction
     */
    static boolean isInNativeTransaction() {
        return MongoNativeTransactionContext.isInNativeTransaction()
    }
    
    /**
     * Check if native session is available (may not be active transaction)
     * @return true if native session exists
     */
    static boolean hasNativeSession() {
        return MongoNativeTransactionContext.hasNativeSession()
    }
}