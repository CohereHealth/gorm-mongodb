package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

import java.time.LocalDateTime

class Appointment implements MongoEntity<Appointment> {
    ObjectId id
    String patientName
    String providerName
    String department
    String status
    LocalDateTime scheduledDate
    Date dateCreated
    Date lastUpdated
    Long version

    static constraints = {
        patientName nullable: false, blank: false
        providerName nullable: false, blank: false
        department nullable: false, blank: false
        status nullable: false, blank: false, inList: ['SCHEDULED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED']
        scheduledDate nullable: false
    }

    static mapping = {
        collection 'appointments'
    }
}
