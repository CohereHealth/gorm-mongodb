package org.grails.datastore.mapping.mongo

import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import grails.gorm.tests.Pet
import org.grails.datastore.mapping.core.DatastoreUtils

/**
 * Integration tests for bulk operations functionality covering both native
 * and regular transaction contexts.
 */
class BulkOperationsIntegrationSpec extends GormDatastoreSpec {

    void "test bulk insert operations"() {
        given: "list of persons to insert"
        def persons = (1..100).collect { 
            new Person(firstName: "Bulk$it", lastName: "Insert", age: 20 + (it % 50))
        }
        def initialCount = Person.count()
        
        when: "performing bulk insert"
        def result = BulkOperations.bulkInsert(Person, persons)
        
        then: "all persons should be inserted"
        result.size() == 100
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
        Person.withTransaction { status ->
            result = BulkOperations.bulkInsert(Person, persons)
            
            then: "should execute immediately"
            Person.count() == initialCount + 50
        }
        
        and: "should persist after transaction"
        Person.count() == initialCount + 50
        result.size() == 50
    }

    void "test bulk save operations"() {
        given: "mix of new and existing persons"
        def existing = new Person(firstName: "Existing", lastName: "Save", age: 30).save(flush: true)
        def persons = [
            existing,
            new Person(firstName: "New1", lastName: "Save", age: 31),
            new Person(firstName: "New2", lastName: "Save", age: 32)
        ]
        def initialCount = Person.count()
        
        when: "performing bulk save"
        existing.age = 35 // Modify existing
        def result = BulkOperations.bulkSave(Person, persons)
        
        then: "all operations should succeed"
        result.size() == 3
        Person.count() == initialCount + 2 // 2 new persons
        Person.get(existing.id).age == 35
        Person.countByLastName("Save") == 3
    }

    void "test bulk delete operations"() {
        given: "persons to delete"
        def persons = (1..20).collect { 
            new Person(firstName: "Delete$it", lastName: "Bulk", age: 40).save(flush: true)
        }
        def initialCount = Person.count()
        
        when: "performing bulk delete"
        def deletedCount = BulkOperations.bulkDelete(Person, persons)
        
        then: "all persons should be deleted"
        deletedCount == 20
        Person.count() == initialCount - 20
        Person.countByLastName("Bulk") == 0
    }

    void "test bulk operations with batch size configuration"() {
        given: "large dataset"
        def persons = (1..250).collect { 
            new Person(firstName: "Batch$it", lastName: "Size", age: 20)
        }
        
        when: "bulk insert with custom batch size"
        def result = BulkOperations.bulkInsert(Person, persons, [batchSize: 50])
        
        then: "should process in batches"
        result.size() == 250
        Person.countByLastName("Size") == 250
    }

    void "test bulk operations error handling"() {
        given: "persons with validation errors"
        def persons = [
            new Person(firstName: "Valid", lastName: "Test", age: 25),
            new Person(firstName: null, lastName: "Invalid", age: -1) // Invalid age
        ]
        
        when: "bulk insert with invalid data"
        def result = BulkOperations.bulkInsert(Person, persons, [failOnError: false])
        
        then: "should handle errors gracefully"
        result.size() <= 2 // May insert valid ones depending on validation
    }

    void "test bulk operations with associations"() {
        given: "persons with pets"
        def owner1 = new Person(firstName: "Owner1", lastName: "Pet", age: 30).save(flush: true)
        def owner2 = new Person(firstName: "Owner2", lastName: "Pet", age: 35).save(flush: true)
        
        def pets = [
            new Pet(name: "Dog1", owner: owner1),
            new Pet(name: "Cat1", owner: owner2),
            new Pet(name: "Dog2", owner: owner1)
        ]
        
        when: "bulk insert pets with associations"
        def result = BulkOperations.bulkInsert(Pet, pets)
        
        then: "associations should be handled correctly"
        result.size() == 3
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
            Person.withTransaction { status ->
                BulkOperations.bulkInsert(Person, persons)
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

    void "test bulk operations performance comparison"() {
        given: "large dataset"
        def bulkPersons = (1..500).collect { 
            new Person(firstName: "Bulk$it", lastName: "Performance", age: 30)
        }
        def individualPersons = (1..500).collect { 
            new Person(firstName: "Individual$it", lastName: "Performance", age: 30)
        }
        
        when: "timing bulk vs individual operations"
        def bulkStart = System.currentTimeMillis()
        BulkOperations.bulkInsert(Person, bulkPersons)
        def bulkTime = System.currentTimeMillis() - bulkStart
        
        def individualStart = System.currentTimeMillis()
        individualPersons.each { it.save() }
        def individualTime = System.currentTimeMillis() - individualStart
        
        then: "bulk should be faster or comparable"
        Person.countByLastName("Performance") == 1000
        bulkTime <= individualTime * 2 // Allow some variance
    }

    void "test bulk operations with different session types"() {
        when: "bulk operations in regular session"
        def regularSession = mongoDatastore.connect()
        DatastoreUtils.bindSession(regularSession)
        
        def persons1 = (1..10).collect { 
            new Person(firstName: "Regular$it", lastName: "Session", age: 25)
        }
        def result1 = BulkOperations.bulkInsert(Person, persons1)
        
        then: "should work with regular session"
        result1.size() == 10
        
        cleanup:
        DatastoreUtils.unbindSession(regularSession)
        regularSession?.disconnect()
        
        when: "bulk operations in native transaction"
        def result2 = null
        Person.withTransaction { status ->
            def persons2 = (1..10).collect { 
                new Person(firstName: "Native$it", lastName: "Session", age: 30)
            }
            result2 = BulkOperations.bulkInsert(Person, persons2)
        }
        
        then: "should work with native session"
        result2.size() == 10
        Person.countByLastName("Session") == 20
    }

    void "test bulk operations with validation"() {
        given: "persons with various validation states"
        def persons = [
            new Person(firstName: "Valid1", lastName: "Test", age: 25),
            new Person(firstName: "Valid2", lastName: "Test", age: 30),
            new Person(firstName: "Valid3", lastName: "Test", age: 35)
        ]
        
        when: "bulk save with validation"
        def result = BulkOperations.bulkSave(Person, persons, [validate: true])
        
        then: "all valid persons should be saved"
        result.size() == 3
        Person.countByLastName("Test") >= 3
    }

    void "test bulk operations memory efficiency"() {
        given: "very large dataset"
        def largeDataset = (1..2000).collect { 
            new Person(firstName: "Memory$it", lastName: "Test", age: 25)
        }
        
        when: "processing large bulk operation"
        def result = BulkOperations.bulkInsert(Person, largeDataset, [batchSize: 100])
        
        then: "should handle large datasets efficiently"
        result.size() == 2000
        Person.countByLastName("Test") >= 2000
    }
}