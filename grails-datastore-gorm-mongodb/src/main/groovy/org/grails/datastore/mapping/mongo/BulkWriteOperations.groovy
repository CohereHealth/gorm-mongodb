package org.grails.datastore.mapping.mongo

import com.mongodb.bulk.BulkWriteResult
import groovy.transform.CompileStatic

/**
 * Trait that adds bulk operation methods to MongoDB domain classes
 */
@CompileStatic
trait BulkWriteOperations {
    
    static BulkWriteResult insertAll(List entities) {
        BulkOperations.insertAll(this, entities)
    }
    
    static BulkWriteResult saveAll(List entities) {
        BulkOperations.saveAll(this, entities)
    }
    
    static BulkWriteResult deleteAll(List ids) {
        BulkOperations.deleteAll(this, ids)
    }
}