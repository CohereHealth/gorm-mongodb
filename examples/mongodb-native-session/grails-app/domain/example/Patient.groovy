package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

class Patient implements MongoEntity<Patient> {
    ObjectId id
    String firstName
    String lastName
    Integer age
    Date dateCreated
    Date lastUpdated
    Long version

    static constraints = {
        firstName nullable: false, blank: false
        lastName nullable: false, blank: false
        age min: 0
    }

    static mapping = {
        collection 'patients'
    }

    String toString() {
        return "${firstName} ${lastName} (${age})"
    }
}
