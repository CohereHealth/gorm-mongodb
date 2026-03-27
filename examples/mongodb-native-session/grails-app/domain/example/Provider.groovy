package example

import grails.mongodb.MongoEntity

class Provider implements MongoEntity<Provider> {
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
        collection 'providers'
    }

    String toString() {
        return "${firstName} ${lastName} (${age})"
    }
}