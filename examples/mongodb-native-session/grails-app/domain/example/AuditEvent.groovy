package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

class AuditEvent implements MongoEntity<AuditEvent> {
    ObjectId id
    String action
    String entityType
    String entityId
    String detail
    String outcome
    Date timestamp = new Date()

    static constraints = {
        action nullable: false, blank: false
        entityType nullable: false, blank: false
        entityId nullable: true
        detail nullable: true
        outcome nullable: false, blank: false, inList: ['SUCCESS', 'FAILURE']
    }

    static mapping = {
        collection 'audit_events'
    }
}
