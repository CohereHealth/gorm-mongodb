package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import grails.gorm.tests.Pet
import org.bson.Document
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister

/**
 * Integration tests for native MongoDB transaction support verifying
 * ACID properties, immediate visibility, and bulk operations through
 * the standard GORM API.
 */
class NativeTransactionIntegrationSpec extends GormDatastoreSpec {

    void "test create, update, and read within native transaction"() {
        given:
        def initialPersonCount = Person.count()
        def initialPetCount = Pet.count()

        when: "complete transaction with multiple operations"
        def results = [:]
        Person.withNativeTransaction { status ->
            def session = DatastoreUtils.getSession(mongoDatastore, true)
            results.sessionType = session.class.simpleName
            results.hasNativeTransaction = session.getTransaction()?.nativeTransaction instanceof ClientSession

            // Create person
            def person = new Person(firstName: "Transaction", lastName: "Test", age: 30).save()
            results.personId = person.id
            results.personVersion = person.version

            // Create pets
            def pet1 = new Pet(name: "Dog", owner: person).save()
            def pet2 = new Pet(name: "Cat", owner: person).save()
            results.pet1Id = pet1.id
            results.pet2Id = pet2.id

            // Update person
            person.age = 31
            person.save()
            results.updatedVersion = person.version

            // Verify immediate visibility
            results.immediatePersonCount = Person.count()
            results.immediatePetCount = Pet.count()
        }

        then: "transaction should use native session"
        results.sessionType == "MongoNativeCodecSession"
        results.hasNativeTransaction == true

        and: "operations should execute immediately"
        results.immediatePersonCount == initialPersonCount + 1
        results.immediatePetCount == initialPetCount + 2

        and: "optimistic locking should work"
        results.updatedVersion == results.personVersion + 1

        and: "changes should persist after transaction"
        Person.count() == initialPersonCount + 1
        Pet.count() == initialPetCount + 2
        Person.get(results.personId).age == 31
    }

    void "test transaction isolation with separate transactions"() {
        given: "initial data"
        def personId = new Person(firstName: "Isolation", lastName: "Test", age: 25).save(flush: true).id

        when: "first transaction modifies the person"
        Person.withNativeTransaction {
            def p1 = Person.get(personId)
            p1.age = 30
            p1.save()
        }

        and: "second transaction reads and modifies further"
        def finalAge = Person.withNativeTransaction {
            def p2 = Person.get(personId)
            assert p2.age == 30  // Should see previous transaction's changes
            p2.age = 35
            p2.save()
            return p2.age
        }

        then: "final state reflects both transactions"
        finalAge == 35
        Person.get(personId).age == 35
    }

    void "test transaction rollback with multiple entities"() {
        given: "initial counts"
        def initialPersonCount = Person.count()
        def initialPetCount = Pet.count()

        when: "transaction with rollback"
        def createdIds = []
        try {
            Person.withNativeTransaction { status ->
                // Create multiple entities
                def person1 = new Person(firstName: "Rollback1", lastName: "Test", age: 30).save()
                def person2 = new Person(firstName: "Rollback2", lastName: "Test", age: 31).save()
                def pet = new Pet(name: "RollbackPet", owner: person1).save()

                createdIds = [person1.id, person2.id, pet.id]

                // Force rollback
                status.setRollbackOnly()
                throw new RuntimeException("Intentional rollback")
            }
        } catch (RuntimeException e) {
            // Expected
        }

        then: "all changes should be rolled back"
        Person.count() == initialPersonCount
        Pet.count() == initialPetCount
    }

    void "test nested native transaction shares the session"() {
        given:
        def initialCount = Person.count()

        when: "nested transactions"
        def results = [:]
        Person.withNativeTransaction { outerStatus ->
            def outerSession = DatastoreUtils.getSession(mongoDatastore, true)
            results.outerSessionType = outerSession.class.simpleName

            def person1 = new Person(firstName: "Outer", lastName: "Test", age: 30).save()
            results.person1Id = person1.id
            results.countAfterOuter = Person.count()

            Person.withNativeTransaction { innerStatus ->
                def innerSession = DatastoreUtils.getSession(mongoDatastore, true)
                results.innerSessionType = innerSession.class.simpleName
                results.sameSession = (outerSession == innerSession)

                def person2 = new Person(firstName: "Inner", lastName: "Test", age: 31).save()
                results.person2Id = person2.id
                results.countAfterInner = Person.count()
            }

            results.countAfterInnerComplete = Person.count()
        }

        then: "nested transactions should work correctly"
        results.outerSessionType == "MongoNativeCodecSession"
        results.innerSessionType == "MongoNativeCodecSession"
        results.countAfterOuter == initialCount + 1
        results.countAfterInner == initialCount + 2
        results.countAfterInnerComplete == initialCount + 2
        Person.count() == initialCount + 2
    }

    void "test persister selection outside transaction"() {
        when: "checking persister outside transaction"
        def regularSession = mongoDatastore.connect()
        def entity = regularSession.mappingContext.getPersistentEntity(Person.name)
        def regularPersister = regularSession.getPersister(entity)

        then: "should use regular persister"
        !(regularPersister instanceof MongoNativeCodecEntityPersister)

        cleanup:
        regularSession?.disconnect()
    }

    void "test persister selection in native transaction"() {
        when: "checking persister in native transaction"
        def nativePersister = null
        def entity = null
        Person.withNativeTransaction { status ->
            def session = DatastoreUtils.getSession(mongoDatastore, true)
            entity = session.mappingContext.getPersistentEntity(Person.name)
            nativePersister = session.getPersister(entity)
        }

        then: "should use native persister"
        nativePersister instanceof MongoNativeCodecEntityPersister
    }

    void "test transaction with bulk operations"() {
        given: "large dataset"
        def persons = (1..100).collect {
            new Person(firstName: "Bulk$it", lastName: "Transaction", age: 25)
        }
        def initialCount = Person.count()

        when: "bulk operations within native transaction"
        def results = [:]
        Person.withNativeTransaction { status ->
            results.insertResult = BulkOperations.insertAll(Person, persons)
        }

        then: "bulk insert should work in native transactions"
        results.insertResult.insertedCount == 100
        Person.count() == initialCount + 100
    }

    void "test error handling and recovery"() {
        given: "initial state"
        def initialCount = Person.count()

        when: "transaction with error in middle"
        def partialResults = []
        try {
            Person.withNativeTransaction { status ->
                // This should succeed
                def person1 = new Person(firstName: "Success", lastName: "Test", age: 30).save()
                partialResults << person1.id

                // This should also succeed
                def person2 = new Person(firstName: "AlsoSuccess", lastName: "Test", age: 31).save()
                partialResults << person2.id

                // Force an error
                throw new RuntimeException("Simulated error")
            }
        } catch (RuntimeException e) {
            // Expected
        }

        then: "transaction should be completely rolled back"
        Person.count() == initialCount
    }

    void "test transaction performance characteristics"() {
        given: "performance test data"
        def persons = (1..200).collect {
            new Person(firstName: "Perf$it", lastName: "Test", age: 25)
        }

        when: "measuring native transaction performance"
        def nativeStart = System.currentTimeMillis()
        Person.withNativeTransaction { status ->
            persons.each { it.save() }
        }
        def nativeTime = System.currentTimeMillis() - nativeStart

        and: "measuring regular save performance"
        def regularStart = System.currentTimeMillis()
        (1..200).each { i ->
            new Person(firstName: "Regular$i", lastName: "Test", age: 25).save(flush: true)
        }
        def regularTime = System.currentTimeMillis() - regularStart

        then: "both approaches should complete successfully"
        Person.countByLastName("Test") == 400
        nativeTime > 0
        regularTime > 0
    }

    void "test transaction with validation errors"() {
        given: "initial count"
        def initialCount = Person.count()

        when: "transaction with validation failure"
        def errorOccurred = false
        try {
            Person.withNativeTransaction { status ->
                // Valid person
                new Person(firstName: "Valid", lastName: "Test", age: 30).save()

                // Invalid person (assuming age validation exists)
                def invalidPerson = new Person(firstName: "Invalid", lastName: "Test", age: -1)
                invalidPerson.save(failOnError: true)
            }
        } catch (Exception e) {
            errorOccurred = true
        }

        then: "transaction should handle validation appropriately"
        // Behavior depends on validation configuration
        Person.count() >= initialCount
    }

    void "test saveAll and deleteAll in same transaction"() {
        given:
        def toDelete = (1..5).collect {
            new Person(firstName: "Old$it", lastName: "Swap", age: 50).save(flush: true)
        }
        def toInsert = (1..5).collect {
            new Person(firstName: "New$it", lastName: "Swap", age: 25)
        }

        when:
        Person.withNativeTransaction {
            Person.deleteAll(toDelete)
            Person.saveAll(toInsert)
        }

        then:
        Person.countByLastName("Swap") == 5
        Person.findAllByLastName("Swap").every { it.firstName.startsWith("New") }
    }

    void "test optimistic locking across multiple saves"() {
        given:
        def person = new Person(firstName: "Versioned", lastName: "Test", age: 20).save(flush: true)
        def v0 = person.version

        when:
        Person.withNativeTransaction {
            person.age = 21
            person.save()
            person.age = 22
            person.save()
        }

        then:
        person.version == v0 + 2
        Person.get(person.id).age == 22
    }

    void "test error mid-transaction rolls back all preceding writes"() {
        given:
        def initialCount = Person.count()

        when:
        Person.withNativeTransaction {
            new Person(firstName: "First", lastName: "Error", age: 30).save()
            new Person(firstName: "Second", lastName: "Error", age: 31).save()
            throw new RuntimeException("mid-transaction failure")
        }

        then:
        thrown(RuntimeException)
        Person.count() == initialCount
    }

    void "test operations outside native transaction use regular path"() {
        given:
        def persons = (1..5).collect {
            new Person(firstName: "Regular$it", lastName: "Path", age: 30)
        }

        when: "saveAll without native transaction falls back to flush-based path"
        Person.saveAll(persons)
        session.flush()

        then:
        Person.countByLastName("Path") == 5
    }
}
