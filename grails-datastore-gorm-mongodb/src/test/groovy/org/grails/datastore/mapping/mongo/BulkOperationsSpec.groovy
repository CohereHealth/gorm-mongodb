package org.grails.datastore.mapping.mongo

import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person

/**
 * Tests for bulk write operations via GORM's saveAll/deleteAll APIs.
 * Within a native transaction these go through
 * {@link org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister#persistEntities}
 * and {@link org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister#deleteEntities},
 * which batch writes into a single {@code bulkWrite} call via
 * {@link org.grails.datastore.mapping.mongo.engine.NativeBulkWriter}.
 */
class BulkOperationsSpec extends GormDatastoreSpec {

    void "test saveAll inserts multiple new entities in native transaction"() {
        given:
        def people = [
            new Person(firstName: "John", lastName: "Doe", age: 30),
            new Person(firstName: "Jane", lastName: "Smith", age: 25),
            new Person(firstName: "Bob", lastName: "Johnson", age: 40)
        ]

        when:
        List ids = null
        Person.withNativeTransaction {
            ids = Person.saveAll(people)
        }

        then:
        ids.size() == 3
        ids.every { it != null }
        Person.count() == 3
    }

    void "test saveAll with mix of inserts and updates in native transaction"() {
        given:
        def existing = new Person(firstName: "Existing", lastName: "User", age: 30).save(flush: true)
        def newPerson = new Person(firstName: "New", lastName: "User", age: 25)

        when:
        existing.age = 35
        Person.withNativeTransaction {
            Person.saveAll([existing, newPerson])
        }

        then:
        Person.count() == 2
        Person.get(existing.id).age == 35
        Person.findByFirstName("New") != null
    }

    void "test deleteAll removes multiple entities in native transaction"() {
        given:
        def p1 = new Person(firstName: "Del1", lastName: "Bulk", age: 20).save(flush: true)
        def p2 = new Person(firstName: "Del2", lastName: "Bulk", age: 21).save(flush: true)
        def p3 = new Person(firstName: "Del3", lastName: "Bulk", age: 22).save(flush: true)

        when:
        Person.withNativeTransaction {
            Person.deleteAll([p1, p2, p3])
        }

        then:
        Person.count() == 0
    }

    void "test saveAll rollback on native transaction failure"() {
        given:
        def initialCount = Person.count()
        def people = (1..5).collect {
            new Person(firstName: "Rollback$it", lastName: "Test", age: 25)
        }

        when:
        Person.withNativeTransaction {
            Person.saveAll(people)
            throw new RuntimeException("force rollback")
        }

        then:
        thrown(RuntimeException)
        Person.count() == initialCount
    }

    void "test deleteAll rollback on native transaction failure"() {
        given:
        def people = (1..3).collect {
            new Person(firstName: "Keep$it", lastName: "Test", age: 30).save(flush: true)
        }
        def initialCount = Person.count()

        when:
        Person.withNativeTransaction {
            Person.deleteAll(people)
            throw new RuntimeException("force rollback")
        }

        then:
        thrown(RuntimeException)
        Person.count() == initialCount
    }

    void "test saveAll skips clean entities"() {
        given:
        def existing = new Person(firstName: "Clean", lastName: "Entity", age: 30).save(flush: true)
        def newPerson = new Person(firstName: "Fresh", lastName: "Entity", age: 25)

        when: "saveAll with a clean (unchanged) existing entity and a new entity"
        Person.withNativeTransaction {
            Person.saveAll([existing, newPerson])
        }

        then: "clean entity is skipped, new entity is inserted"
        Person.count() == 2
        Person.findByFirstName("Fresh") != null
    }

    void "test saveAll with large batch in native transaction"() {
        given:
        def people = (1..200).collect {
            new Person(firstName: "Batch$it", lastName: "Large", age: 20 + (it % 50))
        }

        when:
        Person.withNativeTransaction {
            Person.saveAll(people)
        }

        then:
        Person.countByLastName("Large") == 200
    }

    void "test saveAll and deleteAll in same native transaction"() {
        given:
        def toDelete = (1..3).collect {
            new Person(firstName: "Old$it", lastName: "Mixed", age: 50).save(flush: true)
        }
        def toInsert = (1..3).collect {
            new Person(firstName: "New$it", lastName: "Mixed", age: 25)
        }

        when:
        Person.withNativeTransaction {
            Person.deleteAll(toDelete)
            Person.saveAll(toInsert)
        }

        then:
        Person.countByLastName("Mixed") == 3
        Person.findAllByLastName("Mixed").every { it.firstName.startsWith("New") }
    }
}
