package org.grails.datastore.mapping.mongo

import com.mongodb.bulk.BulkWriteResult
import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person

class BulkOperationsSpec extends GormDatastoreSpec {

    void "test bulk insert operations"() {
        given:
        def people = [
            new Person(firstName: "John", lastName: "Doe"),
            new Person(firstName: "Jane", lastName: "Smith"),
            new Person(firstName: "Bob", lastName: "Johnson")
        ]
        
        when:
        BulkWriteResult result = BulkOperations.insertAll(Person, people)
        
        then:
        result.insertedCount == 3
        Person.count() == 3
    }

    void "test bulk save operations"() {
        given:
        def person1 = new Person(firstName: "John", lastName: "Doe").save(flush: true)
        def person2 = new Person(firstName: "Jane", lastName: "Smith")
        def entities = [person1, person2]
        
        when:
        person1.firstName = "Johnny"
        BulkWriteResult result = BulkOperations.saveAll(Person, entities)
        
        then:
        result.matchedCount + result.insertedCount == 2
        Person.get(person1.id).firstName == "Johnny"
        Person.count() == 2
    }

    void "test bulk delete operations"() {
        given:
        def person1 = new Person(firstName: "John", lastName: "Doe").save(flush: true)
        def person2 = new Person(firstName: "Jane", lastName: "Smith").save(flush: true)
        def ids = [person1.id, person2.id]
        
        when:
        BulkWriteResult result = BulkOperations.deleteAll(Person, ids)
        
        then:
        result.deletedCount == 2
        Person.count() == 0
    }

    void "test bulk operations with transactions"() {
        when:
        def people = [
            new Person(firstName: "John", lastName: "Doe"),
            new Person(firstName: "Jane", lastName: "Smith")
        ]
        
        Person.withTransaction { status ->
            BulkOperations.insertAll(Person, people)
        }
        
        then:
        Person.count() == 2
    }

    @Override
    List getDomainClasses() {
        [Person]
    }
}