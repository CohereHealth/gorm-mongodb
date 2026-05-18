package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

class Medication implements MongoEntity<Medication> {
    ObjectId id
    String name
    String category
    BigDecimal price
    Date dateCreated
    Date lastUpdated
    Long version

    static constraints = {
        name nullable: false, blank: false
        category nullable: false, blank: false
        price nullable: false, min: 0.0
    }

    static mapping = {
        collection 'medications'
        index name: "text"
    }
}
