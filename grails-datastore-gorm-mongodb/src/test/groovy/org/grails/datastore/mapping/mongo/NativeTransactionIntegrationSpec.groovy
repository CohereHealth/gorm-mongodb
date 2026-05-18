package org.grails.datastore.mapping.mongo

import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import grails.gorm.tests.Pet

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

        when:
        def results = [:]
        Person.withNativeTransaction {
            def person = new Person(firstName: "Transaction", lastName: "Test", age: 30).save()
            results.personId = person.id
            results.initialVersion = person.version

            new Pet(name: "Dog", owner: person).save()
            new Pet(name: "Cat", owner: person).save()

            person.age = 31
            person.save()
            results.updatedVersion = person.version

            results.personCountDuring = Person.count()
            results.petCountDuring = Pet.count()
        }

        then: "operations are immediately visible inside the transaction"
        results.personCountDuring == initialPersonCount + 1
        results.petCountDuring == initialPetCount + 2
        results.updatedVersion == results.initialVersion + 1

        and: "changes persist after commit"
        Person.count() == initialPersonCount + 1
        Pet.count() == initialPetCount + 2
        Person.get(results.personId).age == 31
    }

    void "test rollback undoes all writes"() {
        given:
        def initialPersonCount = Person.count()
        def initialPetCount = Pet.count()

        when:
        Person.withNativeTransaction {
            def person = new Person(firstName: "Rollback", lastName: "Test", age: 30).save()
            new Pet(name: "RollbackPet", owner: person).save()
            throw new RuntimeException("force rollback")
        }

        then:
        thrown(RuntimeException)
        Person.count() == initialPersonCount
        Pet.count() == initialPetCount
    }

    void "test nested native transaction shares the session"() {
        given:
        def initialCount = Person.count()

        when:
        def countAfterOuter = 0
        def countAfterInner = 0
        Person.withNativeTransaction {
            new Person(firstName: "Outer", lastName: "Nested", age: 30).save()
            countAfterOuter = Person.count()

            Person.withNativeTransaction {
                new Person(firstName: "Inner", lastName: "Nested", age: 31).save()
                countAfterInner = Person.count()
            }
        }

        then: "both writes visible immediately and committed together"
        countAfterOuter == initialCount + 1
        countAfterInner == initialCount + 2
        Person.count() == initialCount + 2
    }

    void "test saveAll uses bulk write in native transaction"() {
        given:
        def persons = (1..100).collect {
            new Person(firstName: "Bulk$it", lastName: "SaveAll", age: 25)
        }
        def initialCount = Person.count()

        when:
        Person.withNativeTransaction {
            Person.saveAll(persons)
        }

        then:
        Person.count() == initialCount + 100
        Person.countByLastName("SaveAll") == 100
    }

    void "test deleteAll uses bulk write in native transaction"() {
        given:
        def persons = (1..20).collect {
            new Person(firstName: "Bulk$it", lastName: "DeleteAll", age: 40).save(flush: true)
        }
        def initialCount = Person.count()

        when:
        Person.withNativeTransaction {
            Person.deleteAll(persons)
        }

        then:
        Person.count() == initialCount - 20
        Person.countByLastName("DeleteAll") == 0
    }

    void "test saveAll rollback undoes bulk write"() {
        given:
        def initialCount = Person.count()
        def persons = (1..50).collect {
            new Person(firstName: "BulkRollback$it", lastName: "Test", age: 25)
        }

        when:
        Person.withNativeTransaction {
            Person.saveAll(persons)
            throw new RuntimeException("force rollback")
        }

        then:
        thrown(RuntimeException)
        Person.count() == initialCount
    }

    void "test saveAll with mixed inserts and updates"() {
        given:
        def existing = new Person(firstName: "Existing", lastName: "Mixed", age: 30).save(flush: true)
        def newPersons = (1..5).collect {
            new Person(firstName: "New$it", lastName: "Mixed", age: 25)
        }
        def initialCount = Person.count()

        when:
        existing.age = 99
        Person.withNativeTransaction {
            Person.saveAll([existing] + newPersons)
        }

        then:
        Person.count() == initialCount + 5
        Person.get(existing.id).age == 99
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
