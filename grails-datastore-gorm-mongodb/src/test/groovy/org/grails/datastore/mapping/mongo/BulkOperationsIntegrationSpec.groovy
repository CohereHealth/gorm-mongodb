package org.grails.datastore.mapping.mongo

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

        when:
        Person.withNativeTransaction {
            Person.saveAll(people)
        }

        then:
        Person.count() == initialCount + 100
        Person.countByLastName("Insert") == 100
    }

    void "test bulk update via saveAll in native transaction"() {
        given:
        def people = (1..10).collect {
            new Person(firstName: "Update$it", lastName: "Bulk", age: 30).save(flush: true)
        }

        when:
        Person.withNativeTransaction {
            people.each { it.age = 40 }
            Person.saveAll(people)
        }

        then:
        Person.findAllByLastName("Bulk").every { it.age == 40 }
    }

    void "test bulk mixed insert and update via saveAll"() {
        given:
        def existing = new Person(firstName: "Existing", lastName: "Mixed", age: 30).save(flush: true)
        def newPeople = (1..5).collect {
            new Person(firstName: "New$it", lastName: "Mixed", age: 25)
        }
        def initialCount = Person.count()

        when:
        existing.age = 99
        Person.withNativeTransaction {
            Person.saveAll([existing] + newPeople)
        }

        then:
        Person.count() == initialCount + 5
        Person.get(existing.id).age == 99
        Person.countByLastName("Mixed") == 6
    }

    void "test bulk delete via deleteAll in native transaction"() {
        given:
        def people = (1..20).collect {
            new Person(firstName: "Delete$it", lastName: "Bulk", age: 40).save(flush: true)
        }
        def initialCount = Person.count()

        when:
        Person.withNativeTransaction {
            Person.deleteAll(people)
        }

        then:
        Person.count() == initialCount - 20
        Person.countByLastName("Bulk") == 0
    }

    void "test bulk saveAll rollback on error"() {
        given:
        def initialCount = Person.count()
        def people = (1..10).collect {
            new Person(firstName: "Rollback$it", lastName: "Test", age: 25)
        }

        when:
        Person.withNativeTransaction {
            Person.saveAll(people)
            throw new RuntimeException("Force rollback")
        }

        then:
        thrown(RuntimeException)
        Person.count() == initialCount
    }

    void "test bulk deleteAll rollback on error"() {
        given:
        def people = (1..5).collect {
            new Person(firstName: "Keep$it", lastName: "Test", age: 30).save(flush: true)
        }
        def initialCount = Person.count()

        when:
        Person.withNativeTransaction {
            Person.deleteAll(people)
            throw new RuntimeException("Force rollback")
        }

        then:
        thrown(RuntimeException)
        Person.count() == initialCount
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

        when:
        Person.withNativeTransaction {
            Pet.saveAll(pets)
        }

        then:
        Pet.count() == 3
        Pet.countByOwner(owner1) == 2
        Pet.countByOwner(owner2) == 1
    }

    void "test bulk saveAll and deleteAll in same transaction"() {
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

    void "test large batch saveAll in native transaction"() {
        given:
        def people = (1..500).collect {
            new Person(firstName: "Large$it", lastName: "Batch", age: 25)
        }

        when:
        Person.withNativeTransaction {
            Person.saveAll(people)
        }

        then:
        Person.countByLastName("Batch") == 500
    }

    void "test saveAll outside native transaction uses regular path"() {
        given: "no native transaction — falls back to parent persistEntities (one-by-one + flush)"
        def people = (1..5).collect {
            new Person(firstName: "Regular$it", lastName: "Path", age: 30)
        }

        when:
        Person.saveAll(people)
        session.flush()

        then:
        Person.countByLastName("Path") == 5
    }
}
