package org.grails.datastore.mapping.mongo

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@Slf4j
@CompileStatic
class MongoNativeTransactionValidator {
    
    static boolean validateMongoVersion(String mongoVersion) {
        if (!mongoVersion) {
            log.warn("MongoDB version not available for validation")
            return false
        }
        
        try {
            def parts = mongoVersion.split('\\.')
            int major = Integer.parseInt(parts[0])
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0
            
            boolean supported = major > 4 || (major == 4 && minor >= 0)
            
            if (!supported) {
                log.error("Native transactions require MongoDB 4.0+, current version: {}", mongoVersion)
            } else {
                log.debug("MongoDB version {} supports native transactions", mongoVersion)
            }
            
            return supported
        } catch (Exception e) {
            log.error("Failed to parse MongoDB version: {}", mongoVersion, e)
            return false
        }
    }
    
    static boolean validateReplicaSet(boolean isReplicaSet) {
        if (!isReplicaSet) {
            log.error("Native transactions require MongoDB replica set or sharded cluster")
            return false
        }
        log.debug("MongoDB replica set configuration validated for native transactions")
        return true
    }
}