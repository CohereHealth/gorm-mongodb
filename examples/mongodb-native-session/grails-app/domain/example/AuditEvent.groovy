package example

import grails.mongodb.MongoEntity

class AuditEvent implements MongoEntity<AuditEvent> {
    String entityId
    String entityType
    String action
    String performedBy
    Map<String, Object> changes
    Date timestamp
    Date dateCreated
    Date lastUpdated
    Long version

    static constraints = {
        entityId nullable: false
        entityType nullable: false
        action nullable: false
        performedBy nullable: false
        changes nullable: true
        timestamp nullable: false
    }

    static mapping = {
        collection 'audit_events'
    }

    String toString() {
        return "AuditEvent(${entityType}.${action} by ${performedBy})"
    }
}
