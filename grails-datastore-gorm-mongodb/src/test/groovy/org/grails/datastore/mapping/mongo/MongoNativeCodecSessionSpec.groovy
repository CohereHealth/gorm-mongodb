package org.grails.datastore.mapping.mongo

import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import grails.gorm.tests.Pet

/**
 * Tests for native transaction session behavior: immediate visibility,
 * flush being a no-op, bulk operations, and rollback semantics.
 */
class MongoNativeCodecSessionSpec extends GormDatastoreSpec {

    void "test save is immediately visible without flush"() {
        given:
        def initialCount = Person.count()

        when:
        def countDuring = 0
        Person.withNativeTransaction {
            new Person(firstName: "John", lastName: "Doe", age: 30).save(flush: false)
            countDuring = Person.count()
        }

        then:
        countDuring == initialCount + 1
        Person.count() == initialCount + 1
    }

    void "test update is immediately visible without flush"() {
        given:
        def person = new Person(firstName: "Jane", lastName: "Smith", age: 25).save(flush: true)

        when:
        def ageDuring = 0
        Person.withNativeTransaction {
            person.age = 26
            person.save(flush: false)
            ageDuring = Person.get(person.id).age
        }

        then:
        ageDuring == 26
        Person.get(person.id).age == 26
    }

    void "test delete is immediately visible without flush"() {
        given:
        def person = new Person(firstName: "Bob", lastName: "Johnson", age: 35).save(flush: true)
        def personId = person.id
        def initialCount = Person.count()

        when:
        def countDuring = 0
        Person.withNativeTransaction {
            person.delete(flush: false)
            countDuring = Person.count()
        }

        then:
        countDuring == initialCount - 1
        Person.get(personId) == null
    }

    void "test flush is a no-op in native transaction"() {
        when:
        Person.withNativeTransaction {
            new Person(firstName: "Flush", lastName: "Test", age: 30).save()
            session.flush() // should not throw
        }

        then:
        noExceptionThrown()
        Person.countByFirstName("Flush") == 1
    }

    void "test bulk deleteAll is immediate"() {
        given:
        (1..5).each { i ->
            new Person(firstName: "Person$i", lastName: "BulkDel", age: 20 + i).save(flush: true)
        }
        def initialCount = Person.count()

        when:
        def countDuring = 0
        Person.withNativeTransaction {
            Person.where { lastName == "BulkDel" }.deleteAll()
            countDuring = Person.countByLastName("BulkDel")
        }

        then:
        countDuring == 0
        Person.count() == initialCount - 5
    }

    void "test bulk updateAll is immediate"() {
        given:
        (1..3).each { i ->
            new Person(firstName: "Update$i", lastName: "BulkUpd", age: 30).save(flush: true)
        }

        when:
        def allUpdated = false
        Person.withNativeTransaction {
            Person.where { lastName == "BulkUpd" }.updateAll(age: 35)
            allUpdated = Person.findAllByLastName("BulkUpd").every { it.age == 35 }
        }

        then:
        allUpdated
        Person.findAllByLastName("BulkUpd").every { it.age == 35 }
    }

    void "test rollback undoes all changes"() {
        given:
        def initialCount = Person.count()

        when:
        Person.withNativeTransaction {
            new Person(firstName: "Rollback", lastName: "Test", age: 40).save()
            throw new RuntimeException("Force rollback")
        }

        then:
        thrown(RuntimeException)
        Person.count() == initialCount
        Person.findByFirstName("Rollback") == null
    }

    void "test nested transaction sees outer writes"() {
        given:
        def initialCount = Person.count()

        when:
        def innerCount = 0
        Person.withNativeTransaction {
            new Person(firstName: "Outer", lastName: "Nested", age: 25).save()

            Person.withNativeTransaction {
                innerCount = Person.count()
                new Person(firstName: "Inner", lastName: "Nested", age: 26).save()
            }
        }

        then: "inner transaction sees the outer write"
        innerCount == initialCount + 1
        Person.count() == initialCount + 2
    }

    void "test optimistic locking increments version immediately"() {
        given:
        def person = new Person(firstName: "Versioned", lastName: "Test", age: 30).save(flush: true)
        def v0 = person.version

        when:
        Person.withNativeTransaction {
            person.age = 31
            person.save()
        }

        then:
        person.version == v0 + 1
        Person.get(person.id).version == v0 + 1
    }

    void "test error rolls back and cleans up"() {
        given:
        def initialCount = Person.count()

        when:
        Person.withNativeTransaction {
            new Person(firstName: "Error", lastName: "Test", age: 30).save()
            throw new RuntimeException("Simulated error")
        }

        then:
        thrown(RuntimeException)
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
}
