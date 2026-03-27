package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import grails.gorm.tests.Pet
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.transactions.Transaction
import spock.lang.Specification

/**
 * Integration tests for MongoNativeCodecSession covering native transaction behavior,
 * immediate execution, bulk operations, and session management.
 */
class MongoNativeCodecSessionSpec extends GormDatastoreSpec {

    void "test native session is created for native transactions"() {
        when: "a native transaction is started"
        Person.withTransaction { status ->
            def session = DatastoreUtils.getSession(mongoDatastore)
            
            then: "session should be MongoNativeCodecSession"
            session instanceof MongoNativeCodecSession
            
            and: "transaction should have native session"
            def tx = session.getCurrentTransaction()
            tx.nativeTransaction instanceof ClientSession
        }
    }

    void "test immediate execution of save operations"() {
        given: "initial count"
        def initialCount = Person.count()
        
        when: "saving within native transaction"
        Person savedPerson = null
        Person.withTransaction { status ->
            savedPerson = new Person(firstName: "John", lastName: "Doe", age: 30).save(flush: false)
            
            then: "person should be saved immediately without flush"
            savedPerson.id != null
            Person.count() == initialCount + 1
        }
        
        and: "person should still exist after transaction"
        Person.count() == initialCount + 1
        Person.get(savedPerson.id) != null
    }

    void "test immediate execution of update operations"() {
        given: "existing person"
        def person = new Person(firstName: "Jane", lastName: "Smith", age: 25).save(flush: true)
        
        when: "updating within native transaction"
        Person.withTransaction { status ->
            person.age = 26
            person.save(flush: false)
            
            then: "update should be immediate"
            Person.get(person.id).age == 26
        }
        
        and: "update should persist after transaction"
        Person.get(person.id).age == 26
    }

    void "test immediate execution of delete operations"() {
        given: "existing person"
        def person = new Person(firstName: "Bob", lastName: "Johnson", age: 35).save(flush: true)
        def personId = person.id
        def initialCount = Person.count()
        
        when: "deleting within native transaction"
        Person.withTransaction { status ->
            person.delete(flush: false)
            
            then: "delete should be immediate"
            Person.get(personId) == null
            Person.count() == initialCount - 1
        }
        
        and: "delete should persist after transaction"
        Person.get(personId) == null
        Person.count() == initialCount - 1
    }

    void "test flush method logs warning and does nothing"() {
        when: "calling flush in native transaction"
        Person.withTransaction { status ->
            def session = DatastoreUtils.getSession(mongoDatastore)
            
            then: "session should be native session"
            session instanceof MongoNativeCodecSession
            
            when: "flush is called"
            session.flush()
            
            then: "no exception should be thrown"
            noExceptionThrown()
        }
    }

    void "test bulk deleteAll with native session"() {
        given: "multiple persons"
        (1..5).each { i ->
            new Person(firstName: "Person$i", lastName: "Test", age: 20 + i).save(flush: true)
        }
        def initialCount = Person.count()
        
        when: "bulk delete within native transaction"
        long deletedCount = 0
        Person.withTransaction { status ->
            deletedCount = Person.where { lastName == "Test" }.deleteAll()
            
            then: "delete should be immediate"
            deletedCount == 5
            Person.countByLastName("Test") == 0
        }
        
        and: "delete should persist after transaction"
        Person.countByLastName("Test") == 0
        Person.count() == initialCount - 5
    }

    void "test bulk updateAll with native session"() {
        given: "multiple persons"
        (1..3).each { i ->
            new Person(firstName: "Update$i", lastName: "Test", age: 30).save(flush: true)
        }
        
        when: "bulk update within native transaction"
        long updatedCount = 0
        Person.withTransaction { status ->
            updatedCount = Person.where { lastName == "Test" }.updateAll(age: 35)
            
            then: "update should be immediate"
            updatedCount >= 0 // May return -1 on older MongoDB versions
            Person.findAllByLastName("Test").every { it.age == 35 }
        }
        
        and: "update should persist after transaction"
        Person.findAllByLastName("Test").every { it.age == 35 }
    }

    void "test transaction rollback behavior"() {
        given: "initial count"
        def initialCount = Person.count()
        
        when: "transaction is rolled back"
        try {
            Person.withTransaction { status ->
                new Person(firstName: "Rollback", lastName: "Test", age: 40).save()
                status.setRollbackOnly()
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException e) {
            // Expected
        }
        
        then: "changes should be rolled back"
        Person.count() == initialCount
        Person.findByFirstName("Rollback") == null
    }

    void "test association handling in bulk operations"() {
        given: "person with pets"
        def person = new Person(firstName: "Owner", lastName: "Test", age: 30).save(flush: true)
        def pet = new Pet(name: "Fluffy", owner: person).save(flush: true)
        
        when: "bulk update with association"
        Person.withTransaction { status ->
            Pet.where { name == "Fluffy" }.updateAll(owner: person)
            
            then: "association should be handled correctly"
            Pet.findByName("Fluffy").owner.id == person.id
        }
    }

    void "test concurrent native transactions"() {
        given: "initial state"
        def initialCount = Person.count()
        
        when: "multiple concurrent transactions"
        def results = []
        
        // First transaction
        Person.withTransaction { status1 ->
            def person1 = new Person(firstName: "Concurrent1", lastName: "Test", age: 25).save()
            results << person1.id
            
            // Nested transaction (should use same session)
            Person.withTransaction { status2 ->
                def person2 = new Person(firstName: "Concurrent2", lastName: "Test", age: 26).save()
                results << person2.id
                
                then: "both persons should be saved immediately"
                Person.count() == initialCount + 2
            }
        }
        
        then: "all changes should persist"
        Person.count() == initialCount + 2
        results.each { id ->
            assert Person.get(id) != null
        }
    }

    void "test session type selection based on transaction context"() {
        when: "no transaction context"
        def regularSession = mongoDatastore.connect()
        
        then: "should get regular MongoCodecSession"
        regularSession instanceof MongoCodecSession
        !(regularSession instanceof MongoNativeCodecSession)
        
        cleanup:
        regularSession?.disconnect()
        
        when: "within native transaction"
        Person.withTransaction { status ->
            def nativeSession = DatastoreUtils.getSession(mongoDatastore)
            
            then: "should get MongoNativeCodecSession"
            nativeSession instanceof MongoNativeCodecSession
        }
    }

    void "test optimistic locking with native transactions"() {
        given: "person with version"
        def person = new Person(firstName: "Versioned", lastName: "Test", age: 30).save(flush: true)
        def originalVersion = person.version
        
        when: "updating in native transaction"
        Person.withTransaction { status ->
            person.age = 31
            person.save()
            
            then: "version should be incremented immediately"
            person.version == originalVersion + 1
        }
        
        and: "version should persist after transaction"
        def reloaded = Person.get(person.id)
        reloaded.version == originalVersion + 1
    }

    void "test error handling in native transactions"() {
        given: "initial count"
        def initialCount = Person.count()
        
        when: "error occurs in transaction"
        def errorOccurred = false
        try {
            Person.withTransaction { status ->
                new Person(firstName: "Error", lastName: "Test", age: 30).save()
                throw new RuntimeException("Simulated error")
            }
        } catch (RuntimeException e) {
            errorOccurred = true
        }
        
        then: "error should be caught"
        errorOccurred
        
        and: "transaction should be rolled back"
        Person.count() == initialCount
        Person.findByFirstName("Error") == null
    }

    void "test collection access with native session"() {
        when: "accessing collection within native transaction"
        Person.withTransaction { status ->
            def session = DatastoreUtils.getSession(mongoDatastore)
            def entity = session.mappingContext.getPersistentEntity(Person.name)
            def collection = session.getCollection(entity)
            
            then: "collection should be accessible"
            collection != null
            collection.namespace.collectionName == "person"
        }
    }
}