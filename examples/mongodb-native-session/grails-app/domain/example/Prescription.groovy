package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

class Prescription implements MongoEntity<Prescription> {
    ObjectId id
    String patientName
    String providerName
    String medicationName
    String dosage
    Integer refills
    String status
    Date dateCreated
    Date lastUpdated
    Long version

    static constraints = {
        patientName nullable: false, blank: false
        providerName nullable: false, blank: false
        medicationName nullable: false, blank: false
        dosage nullable: false, blank: false
        refills nullable: false, min: 0
        status nullable: false, blank: false, inList: ['ACTIVE', 'FILLED', 'EXPIRED', 'CANCELLED']
    }

    static mapping = {
        collection 'prescriptions'
    }
}
