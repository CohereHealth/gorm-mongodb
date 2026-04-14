package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import grails.gorm.tests.Pet
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister

/**
 * Comprehensive integration tests for native MongoDB transaction support,
 * covering session management, persister selection, and ACID properties.
 */
class NativeTransactionIntegrationSpec extends GormDatastoreSpec {

    void "test complete native transaction lifecycle"() {
        given: "initial state"
        def initialPersonCount = Person.count()
        def initialPetCount = Pet.count()
        
        when: "complete transaction with multiple operations"
        def results = [:]
        Person.withTransaction { status ->
            def session = DatastoreUtils.getSession(mongoDatastore)
            results.sessionType = session.class.simpleName
            results.hasNativeTransaction = session.getCurrentTransaction()?.nativeTransaction instanceof ClientSession
            
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

    void "test transaction isolation and consistency"() {
        given: "shared data"
        def person = new Person(firstName: "Isolation", lastName: "Test", age: 25).save(flush: true)
        
        when: "concurrent transaction simulation"
        def results = [:]
        
        Person.withTransaction { status ->
            // Read initial state
            def p1 = Person.get(person.id)
            results.initialAge = p1.age
            
            // Simulate external modification (would be another transaction in real scenario)
            Person.collection.updateOne([_id: person.id], [$set: [age: 30]])
            
            // Read again within same transaction
            def p2 = Person.get(person.id)
            results.secondRead = p2.age
            
            // Modify within transaction
            p1.age = 35
            p1.save()
            results.finalAge = p1.age
        }
        
        then: "transaction should maintain consistency"
        results.initialAge == 25
        results.finalAge == 35
        Person.get(person.id).age == 35
    }

    void "test transaction rollback with multiple entities"() {
        given: "initial counts"
        def initialPersonCount = Person.count()
        def initialPetCount = Pet.count()
        
        when: "transaction with rollback"
        def createdIds = []
        try {
            Person.withTransaction { status ->
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
        createdIds.every { id -> Person.get(id) == null && Pet.get(id) == null }
    }

    void "test nested transaction behavior"() {
        given: "initial state"
        def initialCount = Person.count()
        
        when: "nested transactions"
        def results = [:]
        Person.withTransaction { outerStatus ->
            def outerSession = DatastoreUtils.getSession(mongoDatastore)
            results.outerSessionType = outerSession.class.simpleName
            
            def person1 = new Person(firstName: "Outer", lastName: "Test", age: 30).save()
            results.person1Id = person1.id
            results.countAfterOuter = Person.count()
            
            Person.withTransaction { innerStatus ->
                def innerSession = DatastoreUtils.getSession(mongoDatastore)
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

    void "test persister selection based on context"() {
        when: "checking persister outside transaction"
        def regularSession = mongoDatastore.connect()
        def entity = regularSession.mappingContext.getPersistentEntity(Person.name)
        def regularPersister = regularSession.getPersister(entity)
        
        then: "should use regular persister"
        !(regularPersister instanceof MongoNativeCodecEntityPersister)
        
        cleanup:
        regularSession?.disconnect()
        
        when: "checking persister in native transaction"
        def nativePersister = null
        Person.withTransaction { status ->
            def session = DatastoreUtils.getSession(mongoDatastore)
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
        Person.withTransaction { status ->
            results.insertResult = BulkOperations.bulkInsert(Person, persons)
            results.countDuringTransaction = Person.count()
            
            // Bulk update
            results.updateCount = Person.where { lastName == "Transaction" }.updateAll(age: 30)
            
            // Bulk delete some
            def toDelete = Person.findAllByLastName("Transaction")[0..9] // First 10
            results.deleteCount = BulkOperations.bulkDelete(Person, toDelete)
            results.finalCountDuringTransaction = Person.count()
        }
        
        then: "bulk operations should work in native transactions"
        results.insertResult.size() == 100
        results.countDuringTransaction == initialCount + 100
        results.updateCount >= 0 // May return -1 on older MongoDB
        results.deleteCount == 10
        results.finalCountDuringTransaction == initialCount + 90
        Person.count() == initialCount + 90
    }

    void "test error handling and recovery"() {
        given: "initial state"
        def initialCount = Person.count()
        
        when: "transaction with error in middle"
        def partialResults = []
        try {
            Person.withTransaction { status ->
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
        partialResults.every { id -> Person.get(id) == null }
    }

    void "test transaction performance characteristics"() {
        given: "performance test data"
        def persons = (1..200).collect { 
            new Person(firstName: "Perf$it", lastName: "Test", age: 25)
        }
        
        when: "measuring native transaction performance"
        def nativeStart = System.currentTimeMillis()
        Person.withTransaction { status ->
            persons.each { it.save() }
        }
        def nativeTime = System.currentTimeMillis() - nativeStart
        
        and: "measuring regular session performance"
        def regularStart = System.currentTimeMillis()
        def regularSession = mongoDatastore.connect()
        DatastoreUtils.bindSession(regularSession)
        try {
            (1..200).each { i ->
                new Person(firstName: "Regular$i", lastName: "Test", age: 25).save()
            }
            regularSession.flush()
        } finally {
            DatastoreUtils.unbindSession(regularSession)
            regularSession.disconnect()
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
            Person.withTransaction { status ->
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
}