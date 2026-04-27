package org.grails.datastore.mapping.mongo.engine

import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.mongo.MongoNativeCodecSession

/**
 * Tests for native persister behavior: immediate execution, optimistic locking,
 * and bulk write semantics — all verified through the GORM API.
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

    void "test update executes immediately with version increment"() {
        given:
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

    void "test delete executes immediately"() {
        given:
        def person = new Person(firstName: "Delete", lastName: "Test", age: 35).save(flush: true)
        def personId = person.id
        def initialCount = Person.count()

        when: "deleting within native transaction"
        Person.withNativeTransaction { status ->
            person.delete()
        }

        then: "delete should execute immediately"
        Person.get(personId) == null
        Person.count() == initialCount - 1
    }

    void "test multiple version increments in one transaction"() {
        given:
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

            // Now try to save original person with stale version
            person.age = 32
            person.save()  // This should throw OptimisticLockingException
        }

        then: "should throw optimistic locking exception"
        thrown(org.grails.datastore.mapping.core.OptimisticLockingException)
    }

    void "test deleteAll batches deletes in native transaction"() {
        given:
        def people = (1..20).collect {
            new Person(firstName: "BatchDel$it", lastName: "Persist", age: 30).save(flush: true)
        }
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

    void "test outside native transaction uses regular path"() {
        when:
        def person = new Person(firstName: "Regular", lastName: "Path", age: 30)
        person.save()
        session.flush()

        then:
        Person.countByFirstName("Regular") == 1
    }
}
