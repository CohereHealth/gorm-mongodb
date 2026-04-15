package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

class Referral implements MongoEntity<Referral> {
    ObjectId id
    String fromFacility
    String toFacility
    String patientName
    String reason
    String status
    Date dateCreated
    Date lastUpdated
    Long version

    static constraints = {
        fromFacility nullable: false, blank: false
        toFacility nullable: false, blank: false
        patientName nullable: false, blank: false
        reason nullable: false, blank: false
        status nullable: false, blank: false, inList: ['PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED']
    }

    static mapping = {
        collection 'referrals'
    }
}
