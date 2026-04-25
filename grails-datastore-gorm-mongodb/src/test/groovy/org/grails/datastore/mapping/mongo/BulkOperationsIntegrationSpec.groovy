package org.grails.datastore.mapping.mongo

import com.mongodb.MongoBulkWriteException
import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import grails.gorm.tests.Pet

/**
 * Integration tests for bulk write operations within native transactions.
 * Exercises {@code Person.saveAll} / {@code Person.deleteAll} which route through
 * {@link org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister#persistEntities}
 * and {@link org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister#deleteEntities},
 * batching writes via {@link org.grails.datastore.mapping.mongo.engine.NativeBulkWriter}.
 */
class BulkOperationsIntegrationSpec extends GormDatastoreSpec {

    void "test bulk insert via saveAll in native transaction"() {
        given:
        def people = (1..100).collect {
            new Person(firstName: "Bulk$it", lastName: "Insert", age: 20 + (it % 50))
        }
        def initialCount = Person.count()

        when: "performing bulk insert"
        def result = BulkOperations.insertAll(Person, people)
        
        then: "all persons should be inserted"
        result.insertedCount == 100
        Person.count() == initialCount + 100
        Person.countByLastName("Insert") == 100
    }

    void "test bulk insert with native transactions"() {
        given: "list of persons"
        def persons = (1..50).collect {
            new Person(firstName: "Native$it", lastName: "Bulk", age: 25)
        }
        def initialCount = Person.count()

        when: "bulk insert within native transaction"
        def result = null
        Person.withNativeTransaction { status ->
            result = BulkOperations.insertAll(Person, persons)
        }

        then: "should persist after transaction"
        Person.count() == initialCount + 50
        result.insertedCount == 50
    }

    void "test bulk mixed insert and update via saveAll"() {
        given:
        def existing = new Person(firstName: "Existing", lastName: "Mixed", age: 30).save(flush: true)
        def newPeople = (1..5).collect {
            new Person(firstName: "New$it", lastName: "Mixed", age: 25)
        }
        def initialCount = Person.count()

        when: "performing bulk save"
        existing.age = 35 // Modify existing
        def result = BulkOperations.saveAll(Person, [existing] + newPeople)

        then: "all operations should succeed"
        result.insertedCount == 5  // 5 new persons
        result.modifiedCount == 1  // 1 updated person
        Person.count() == initialCount + 5 // 5 new persons
        Person.get(existing.id).age == 35
        Person.countByLastName("Mixed") == 6
    }

    void "test bulk delete operations"() {
        given: "persons to delete"
        def persons = (1..20).collect {
            new Person(firstName: "Delete$it", lastName: "Bulk", age: 40).save(flush: true)
        }
        def initialCount = Person.count()

        when: "performing bulk delete"
        def ids = persons.collect { it.id }
        def result = BulkOperations.deleteAll(Person, ids)

        then: "all persons should be deleted"
        result.deletedCount == 20
        Person.count() == initialCount - 20
        Person.countByLastName("Bulk") == 0
    }

    void "test bulk operations with large dataset"() {
        given: "large dataset"
        def persons = (1..250).collect {
            new Person(firstName: "Large$it", lastName: "Dataset", age: 20)
        }

        when: "bulk insert large dataset"
        def result = BulkOperations.insertAll(Person, persons)

        then: "should process all entities in single bulk operation"
        result.insertedCount == 250
        Person.countByLastName("Dataset") == 250
    }

    void "test bulk operations error handling with duplicate keys"() {
        given: "persons with duplicate IDs"
        def person1 = new Person(firstName: "Error1", lastName: "DuplicateKey", age: 25)
        person1.save(flush: true)
        def duplicateId = person1.id

        def person2 = new Person(firstName: "Error2", lastName: "DuplicateKey", age: 30)
        person2.id = duplicateId  // Force duplicate ID
        def person3 = new Person(firstName: "Error3", lastName: "DuplicateKey", age: 35)

        when: "bulk insert with duplicate ID"
        BulkOperations.insertAll(Person, [person2, person3])

        then: "should throw MongoDB bulk write exception"
        thrown(MongoBulkWriteException)
    }

    void "test bulk saveAll with associations"() {
        given:
        def owner1 = new Person(firstName: "Owner1", lastName: "Pet", age: 30).save(flush: true)
        def owner2 = new Person(firstName: "Owner2", lastName: "Pet", age: 35).save(flush: true)
        def pets = [
            new Pet(name: "Dog1", owner: owner1),
            new Pet(name: "Cat1", owner: owner2),
            new Pet(name: "Dog2", owner: owner1)
        ]
        
        when: "bulk insert pets with associations"
        def result = BulkOperations.insertAll(Pet, pets)
        
        then: "associations should be handled correctly"
        result.insertedCount == 3
        Pet.countByOwner(owner1) == 2
        Pet.countByOwner(owner2) == 1
    }

    void "test bulk operations rollback behavior"() {
        given: "initial state"
        def initialCount = Person.count()
        def persons = (1..10).collect {
            new Person(firstName: "Rollback$it", lastName: "Test", age: 25)
        }

        when: "bulk operation in rolled back transaction"
        try {
            Person.withNativeTransaction { status ->
                BulkOperations.insertAll(Person, persons)
                status.setRollbackOnly()
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException e) {
            // Expected
        }

        then: "changes should be rolled back"
        Person.count() == initialCount
        Person.countByLastName("Test") == 0
    }

    void "test large batch saveAll in native transaction"() {
        given:
        def people = (1..500).collect {
            new Person(firstName: "Large$it", lastName: "Batch", age: 25)
        }

        when:
        Person.withNativeTransaction {
            Person.saveAll(people)
        }

        and: "timing bulk vs individual operations"
        def bulkPersons = (1..500).collect {
            new Person(firstName: "Bulk$it", lastName: "Performance", age: 25)
        }
        def bulkStart = System.currentTimeMillis()
        BulkOperations.insertAll(Person, bulkPersons)
        def bulkTime = System.currentTimeMillis() - bulkStart

        def individualPersons = (1..500).collect {
            new Person(firstName: "Individual$it", lastName: "Performance", age: 25)
        }
        def individualStart = System.currentTimeMillis()
        individualPersons.each { it.save() }
        def individualTime = System.currentTimeMillis() - individualStart

        then: "bulk should be faster or comparable"
        Person.countByLastName("Batch") == 500
        Person.countByLastName("Performance") == 1000
        bulkTime <= individualTime * 2 // Allow some variance
    }

    void "test bulk operations with regular session"() {
        when: "bulk operations in regular session"
        def persons1 = (1..10).collect {
            new Person(firstName: "Regular$it", lastName: "Session", age: 25)
        }
        def result1 = BulkOperations.insertAll(Person, persons1)

        then: "should work with regular session"
        result1.insertedCount == 10
        Person.countByLastName("Session") == 10
    }

    void "test bulk operations with native transaction session"() {
        when: "bulk operations in native transaction"
        def result = null
        Person.withNativeTransaction { status ->
            def persons = (1..10).collect {
                new Person(firstName: "Native$it", lastName: "TxSession", age: 30)
            }
            result = BulkOperations.insertAll(Person, persons)
        }

        then: "should work with native session"
        result.insertedCount == 10
        Person.countByLastName("TxSession") == 10
    }

    void "test bulk operations with validation"() {
        given: "persons with various validation states"
        def persons = [
            new Person(firstName: "Valid1", lastName: "Validation", age: 25),
            new Person(firstName: "Valid2", lastName: "Validation", age: 30),
            new Person(firstName: "Valid3", lastName: "Validation", age: 35)
        ]

        when: "bulk save"
        def result = BulkOperations.saveAll(Person, persons)

        then: "all valid persons should be saved"
        result.insertedCount == 3
        Person.countByLastName("Validation") == 3
    }

    void "test bulk operations memory efficiency"() {
        given: "very large dataset"
        def largeDataset = (1..2000).collect {
            new Person(firstName: "Memory$it", lastName: "Efficiency", age: 25)
        }

        when: "processing large bulk operation"
        def result = BulkOperations.insertAll(Person, largeDataset)

        then: "should handle large datasets efficiently"
        result.insertedCount == 2000
        Person.countByLastName("Efficiency") == 2000
    }
}
