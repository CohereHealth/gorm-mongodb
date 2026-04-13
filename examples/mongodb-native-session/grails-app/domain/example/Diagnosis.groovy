package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

class Diagnosis implements MongoEntity<Diagnosis> {
    ObjectId id
    String icdCode
    String description
    String category
    String severity
    Date dateCreated
    Date lastUpdated
    Long version

    static hasMany = [procedures: Procedure]

    static constraints = {
        icdCode nullable: false, blank: false
        description nullable: false, blank: false
        category nullable: false, blank: false
        severity nullable: false, blank: false, inList: ['LOW', 'MODERATE', 'HIGH', 'CRITICAL']
    }

    static mapping = {
        collection 'diagnoses'
    }
}
