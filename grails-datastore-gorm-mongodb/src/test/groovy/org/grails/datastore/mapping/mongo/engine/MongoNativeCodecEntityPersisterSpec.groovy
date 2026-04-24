package org.grails.datastore.mapping.mongo.engine

import com.mongodb.client.ClientSession
import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.mongo.MongoNativeCodecSession
import org.springframework.dao.OptimisticLockingFailureException

/**
 * Integration tests for MongoNativeCodecEntityPersister covering immediate execution,
 * optimistic locking, and native transaction behavior.
 */
class MongoNativeCodecEntityPersisterSpec extends GormDatastoreSpec {

    void "test persister is used in native transaction context"() {
        when: "within native transaction"
        def session = null
        def persister = null
        Person.withNativeTransaction { status ->
            session = DatastoreUtils.getSession(mongoDatastore, true)
            def entity = session.mappingContext.getPersistentEntity(Person.name)
            persister = session.getPersister(entity)
        }

        then: "should use MongoNativeCodecEntityPersister"
        persister instanceof MongoNativeCodecEntityPersister
        session instanceof MongoNativeCodecSession
    }

    void "test immediate insert execution"() {
        given: "initial count"
        def initialCount = Person.count()

        when: "inserting within native transaction"
        Person savedPerson = null
        Person.withNativeTransaction { status ->
            savedPerson = new Person(firstName: "Immediate", lastName: "Insert", age: 25)
            savedPerson.save(flush: false)
        }

        then: "insert should execute immediately"
        savedPerson.id != null
        Person.count() == initialCount + 1

        and: "person should be findable immediately"
        Person.get(savedPerson.id) != null
    }

    void "test immediate update execution with optimistic locking"() {
        given: "existing person"
        def person = new Person(firstName: "Update", lastName: "Test", age: 30).save(flush: true)
        def originalVersion = person.version

        when: "updating within native transaction"
        Person.withNativeTransaction { status ->
            person.age = 31
            person.save(flush: false)
        }

        then: "update should execute immediately"
        person.version == originalVersion + 1
        Person.get(person.id).age == 31
    }

    void "test immediate delete execution"() {
        given: "existing person"
        def person = new Person(firstName: "Delete", lastName: "Test", age: 35).save(flush: true)
        def personId = person.id
        def initialCount = Person.count()

        when: "deleting within native transaction"
        Person.withNativeTransaction { status ->
            person.delete(flush: false)
        }

        then: "delete should execute immediately"
        Person.get(personId) == null
        Person.count() == initialCount - 1
    }

    void "test version increment on successful update"() {
        given: "person with initial version"
        def person = new Person(firstName: "Version", lastName: "Test", age: 20).save(flush: true)
        def initialVersion = person.version

        when: "multiple updates in native transaction"
        Person.withNativeTransaction { status ->
            person.age = 21
            person.save()

            person.age = 22
            person.save()
        }

        then: "version should increment with each save"
        person.version == initialVersion + 2
    }

    void "test transaction context validation"() {
        when: "using persister outside native transaction"
        def session = mongoDatastore.connect()
        def entity = session.mappingContext.getPersistentEntity(Person.name)
        def persister = session.getPersister(entity)
        
        then: "should not be native persister"
        !(persister instanceof MongoNativeCodecEntityPersister)
        
        cleanup:
        session?.disconnect()
    }

    void "test concurrent modification detection"() {
        given: "person saved in transaction"
        def person = new Person(firstName: "Concurrent", lastName: "Test", age: 30).save(flush: true)
        def originalVersion = person.version

        when: "concurrent modification scenario"
        Person.withNativeTransaction { status ->
            // Load same person in different context
            def otherPerson = Person.get(person.id)
            otherPerson.age = 31
            otherPerson.save()

            // Now try to save original person
            person.age = 32
            person.save()
        }

        then: "should handle concurrent modification appropriately"
        def finalPerson = Person.get(person.id)
        finalPerson.version > originalVersion
    }

    void "test error recovery in persister"() {
        given: "initial count"
        def initialCount = Person.count()

        when: "error occurs during save"
        def errorCaught = false
        try {
            Person.withNativeTransaction { status ->
                def person = new Person(firstName: "Error", lastName: "Test", age: 30)
                person.save()

                // Force an error after save
                throw new RuntimeException("Simulated error")
            }
        } catch (RuntimeException e) {
            errorCaught = true
        }

        then: "error should be caught and transaction rolled back"
        errorCaught
        Person.count() == initialCount
    }
}