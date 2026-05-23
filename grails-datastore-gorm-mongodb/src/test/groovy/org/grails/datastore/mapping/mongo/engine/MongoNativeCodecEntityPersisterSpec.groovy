package org.grails.datastore.mapping.mongo.engine

import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person

/**
 * Tests for native persister behavior: immediate execution, optimistic locking,
 * and bulk write semantics — all verified through the GORM API.
 */
class MongoNativeCodecEntityPersisterSpec extends GormDatastoreSpec {

    void "test insert executes immediately without flush"() {
        given:
        def initialCount = Person.count()

        when:
        def results = [:]
        Person.withNativeTransaction {
            def person = new Person(firstName: "Immediate", lastName: "Insert", age: 25)
            person.save(flush: false)
            results.id = person.id
            results.countDuring = Person.count()
        }

        then:
        results.id != null
        results.countDuring == initialCount + 1
    }

    void "test update executes immediately with version increment"() {
        given:
        def person = new Person(firstName: "Update", lastName: "Test", age: 30).save(flush: true)
        def v0 = person.version

        when:
        Person.withNativeTransaction {
            person.age = 31
            person.save(flush: false)
        }

        then:
        person.version == v0 + 1
        Person.get(person.id).age == 31
    }

    void "test delete executes immediately"() {
        given:
        def person = new Person(firstName: "Delete", lastName: "Test", age: 35).save(flush: true)
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
        session.clear()
        Person.get(personId) == null
    }

    void "test multiple version increments in one transaction"() {
        given:
        def person = new Person(firstName: "Version", lastName: "Test", age: 20).save(flush: true)
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
    }

    void "test saveAll batches inserts in native transaction"() {
        given:
        def people = (1..50).collect {
            new Person(firstName: "Batch$it", lastName: "Persist", age: 25)
        }

        when:
        Person.withNativeTransaction {
            Person.saveAll(people)
        }

        then:
        Person.countByLastName("Persist") == 50
    }

    void "test deleteAll batches deletes in native transaction"() {
        given:
        def people = (1..20).collect {
            new Person(firstName: "BatchDel$it", lastName: "Persist", age: 30).save(flush: true)
        }
        def initialCount = Person.count()

        when:
        Person.withNativeTransaction {
            Person.deleteAll(people)
        }

        then:
        Person.count() == initialCount - 20
    }

    void "test error rolls back all writes"() {
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
