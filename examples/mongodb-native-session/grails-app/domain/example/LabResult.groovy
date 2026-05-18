package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

class LabResult implements MongoEntity<LabResult> {
    ObjectId id
    String patientName
    String testName
    String result
    String status
    String orderedBy
    Date dateCreated
    Date lastUpdated
    Long version

    static constraints = {
        patientName nullable: false, blank: false
        testName nullable: false, blank: false
        result nullable: true
        status nullable: false, blank: false, inList: ['ORDERED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED']
        orderedBy nullable: false, blank: false
    }

    static mapping = {
        collection 'lab_results'
    }
}
