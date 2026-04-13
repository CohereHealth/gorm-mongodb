package example

import grails.mongodb.MongoEntity
import org.bson.types.ObjectId

class Procedure implements MongoEntity<Procedure> {
    ObjectId id
    String cptCode
    String name
    BigDecimal cost
    Date dateCreated
    Date lastUpdated
    Long version

    static belongsTo = [diagnosis: Diagnosis]

    static constraints = {
        cptCode nullable: false, blank: false
        name nullable: false, blank: false
        cost nullable: false, min: 0.0
    }

    static mapping = {
        collection 'procedures'
    }
}
