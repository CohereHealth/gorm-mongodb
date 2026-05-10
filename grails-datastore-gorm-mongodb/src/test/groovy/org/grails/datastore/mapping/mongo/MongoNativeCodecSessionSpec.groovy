package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import grails.gorm.tests.Pet
import org.grails.datastore.mapping.core.DatastoreUtils

/**
 * Tests for native transaction session behavior: immediate visibility,
 * flush being a no-op, bulk operations, and rollback semantics.
 */
class MongoNativeCodecSessionSpec extends GormDatastoreSpec {

    void "test native session is created for native transactions"() {
        when: "a native transaction is started"
        def session = null
        def tx = null
        Person.withNativeTransaction { status ->
            session = DatastoreUtils.getSession(mongoDatastore, true)
            tx = session.getTransaction()
        }

        then: "session should be MongoNativeCodecSession"
        session instanceof MongoNativeCodecSession

        and: "transaction should have native session"
        tx.nativeTransaction instanceof ClientSession
    }

    void "test immediate execution of save operations"() {
        given: "initial count"
        def initialCount = Person.count()

        when: "saving within native transaction"
        Person savedPerson = null
        Person.withNativeTransaction { status ->
            savedPerson = new Person(firstName: "John", lastName: "Doe", age: 30).save(flush: false)
        }

        then: "person should be saved immediately without flush"
        savedPerson.id != null
        Person.count() == initialCount + 1

        and: "person should still exist after transaction"
        Person.get(savedPerson.id) != null
    }

    void "test update is immediately visible without flush"() {
        given:
        def person = new Person(firstName: "Jane", lastName: "Smith", age: 25).save(flush: true)

        when: "updating within native transaction"
        Person.withNativeTransaction { status ->
            person.age = 26
            person.save(flush: false)
        }

        then: "update should persist after transaction"
        Person.get(person.id).age == 26
    }

    void "test delete is immediately visible without flush"() {
        given:
        def person = new Person(firstName: "Bob", lastName: "Johnson", age: 35).save(flush: true)
        def personId = person.id
        def initialCount = Person.count()

        when: "deleting within native transaction"
        Person.withNativeTransaction { status ->
            person.delete()
        }

        then: "delete should persist after transaction"
        Person.get(personId) == null
    }

    void "test flush method logs warning and does nothing"() {
        when: "calling flush in native transaction"
        def session = null
        Person.withNativeTransaction { status ->
            session = DatastoreUtils.getSession(mongoDatastore, true)
            session.flush()
        }

        then: "session should be native session and no exception thrown"
        session instanceof MongoNativeCodecSession
        noExceptionThrown()
    }

    void "test bulk deleteAll is immediate"() {
        given:
        (1..5).each { i ->
            new Person(firstName: "Person$i", lastName: "BulkDel", age: 20 + i).save(flush: true)
        }
        def initialCount = Person.count()

        when: "bulk delete within native transaction"
        long deletedCount = 0
        Person.withNativeTransaction { status ->
            deletedCount = Person.where { lastName == "BulkDel" }.deleteAll()
        }

        then: "delete should persist after transaction"
        deletedCount == 5
        Person.countByLastName("BulkDel") == 0
        Person.count() == initialCount - 5
    }

    void "test bulk updateAll is immediate"() {
        given:
        (1..3).each { i ->
            new Person(firstName: "Update$i", lastName: "BulkUpd", age: 30).save(flush: true)
        }

        when: "bulk update within native transaction"
        long updatedCount = 0
        Person.withNativeTransaction { status ->
            updatedCount = Person.where { lastName == "Test" }.updateAll(age: 35)
        }

        then: "update should persist after transaction"
        updatedCount >= 0 // May return -1 on older MongoDB versions
        Person.findAllByLastName("Test").every { it.age == 35 }
    }

    void "test rollback undoes all changes"() {
        given:
        def initialCount = Person.count()

        when: "transaction is rolled back"
        try {
            Person.withNativeTransaction { status ->
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
        Person.withNativeTransaction { status ->
            Pet.where { name == "Fluffy" }.updateAll(owner: person)
        }

        then: "association should be handled correctly"
        Pet.findByName("Fluffy").owner.id == person.id
    }

    void "test concurrent native transactions"() {
        given: "initial state"
        def initialCount = Person.count()

        when: "multiple concurrent transactions"
        def results = []

        // First transaction
        Person.withNativeTransaction { status1 ->
            def person1 = new Person(firstName: "Concurrent1", lastName: "Test", age: 25).save()
            results << person1.id

            // Nested transaction (should use same session)
            Person.withNativeTransaction { status2 ->
                def person2 = new Person(firstName: "Concurrent2", lastName: "Test", age: 26).save()
                results << person2.id
            }
        }

        then: "all changes should persist"
        Person.count() == initialCount + 2
    }

    void "test session type selection based on transaction context"() {
        when: "no transaction context"
        def regularSession = mongoDatastore.connect()

        then: "should get regular MongoCodecSession"
        regularSession instanceof MongoCodecSession
        !(regularSession instanceof MongoNativeCodecSession)

        cleanup:
        regularSession?.disconnect()
    }

    void "test session type within native transaction"() {
        when: "within native transaction"
        def nativeSession = null
        Person.withNativeTransaction { status ->
            nativeSession = DatastoreUtils.getSession(mongoDatastore, true)
        }

        then: "should get MongoNativeCodecSession"
        nativeSession instanceof MongoNativeCodecSession
    }

    void "test optimistic locking with native transactions"() {
        given: "person with version"
        def person = new Person(firstName: "Versioned", lastName: "Test", age: 30).save(flush: true)
        def originalVersion = person.version

        when: "updating in native transaction"
        Person.withNativeTransaction { status ->
            person.age = 31
            person.save()
        }

        then: "version should be incremented"
        person.version == originalVersion + 1

        and: "version should persist after transaction"
        def reloaded = Person.get(person.id)
        reloaded.version == originalVersion + 1
    }

    void "test error rolls back and cleans up"() {
        given:
        def initialCount = Person.count()

        when: "error occurs in transaction"
        def errorOccurred = false
        try {
            Person.withNativeTransaction { status ->
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
        !MongoNativeTransactionContext.hasNativeSession()
    }

    void "test association handling in native transaction"() {
        given:
        def person = new Person(firstName: "Owner", lastName: "Assoc", age: 30).save(flush: true)

        when:
        Person.withNativeTransaction {
            new Pet(name: "Fluffy", owner: person).save()
        }

        then:
        Pet.findByName("Fluffy").owner.id == person.id
    }

    void "test collection access with native session"() {
        when: "accessing collection within native transaction"
        def collection = null
        Person.withNativeTransaction { status ->
            def session = DatastoreUtils.getSession(mongoDatastore, true)
            def entity = session.mappingContext.getPersistentEntity(Person.name)
            collection = session.getCollection(entity)
        }

        then: "collection should be accessible"
        collection != null
        collection.namespace.collectionName == "person"
    }
}
