package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

class Facility implements MongoEntity<Facility> {
    ObjectId id
    String name
    String npi
    String facilityType
    Integer bedCount
    FacilityAddress address
    Date dateCreated
    Date lastUpdated
    Long version

    static embedded = ['address']

    static constraints = {
        name nullable: false, blank: false
        npi nullable: false, blank: false
        facilityType nullable: false, blank: false, inList: ['HOSPITAL', 'CLINIC', 'URGENT_CARE', 'PHARMACY']
        bedCount nullable: true, min: 0
        address nullable: true
    }

    static mapping = {
        collection 'facilities'
    }
}
