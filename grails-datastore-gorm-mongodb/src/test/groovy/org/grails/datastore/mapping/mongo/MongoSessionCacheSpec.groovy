package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec

/**
 * Tests to verify first-level session cache behavior in native transactions.
 *
 * The session cache should ensure:
 * 1. After save(), subsequent get() returns the SAME instance (not a new query)
 * 2. Updates to cached objects are visible without re-querying
 * 3. Version tracking happens automatically via cached instance references
 */
class MongoSessionCacheSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [CachedPerson]
    }

    def "test session cache returns same instance after save"() {
        when: "save an entity and retrieve it"
        def result = CachedPerson.withNativeTransaction { session ->
            def p1 = new CachedPerson(name: "Alice", age: 30).save()
            def savedId = p1.id
            def savedVersion = p1.version

            // Retrieve - should return SAME instance from cache
            def p2 = CachedPerson.get(savedId)

            return [
                p1: p1,
                p2: p2,
                sameInstance: p1.is(p2),  // Critical: should be TRUE
                p1Version: savedVersion,
                p2Version: p2.version
            ]
        }

        then: "get() returns the same cached instance"
        result.sameInstance == true
        result.p1 == result.p2
        result.p1Version == result.p2Version
    }

    def "test session cache prevents double-update conflict"() {
        when: "save, retrieve, and update the same entity"
        def result = CachedPerson.withNativeTransaction { session ->
            // Initial save
            def p1 = new CachedPerson(name: "Bob", age: 25).save()
            def id = p1.id

            // First update
            p1.age = 26
            p1.save()
            def version1 = p1.version

            // Retrieve - should get SAME instance
            def p2 = CachedPerson.get(id)

            // Second update - should work because p2 is p1
            p2.age = 27
            p2.save()
            def version2 = p2.version

            return [
                sameInstance: p1.is(p2),
                version1: version1,
                version2: version2,
                finalAge: p2.age
            ]
        }

        then: "no optimistic locking exception occurs"
        result.sameInstance == true
        result.version2 > result.version1
        result.finalAge == 27
    }

    def "test cache isolation between transactions"() {
        given: "entity created in first transaction"
        def id = CachedPerson.withNativeTransaction {
            new CachedPerson(name: "Charlie", age: 40).save().id
        }

        when: "load in second transaction and modify"
        CachedPerson.withNativeTransaction {
            def person = CachedPerson.get(id)
            person.age = 41
            person.save()
        }

        and: "load in third transaction"
        def finalAge = CachedPerson.withNativeTransaction {
            CachedPerson.get(id).age
        }

        then: "sees the updated value"
        finalAge == 41
    }

    def "test multiple gets return same instance"() {
        when: "get the same entity multiple times"
        def result = CachedPerson.withNativeTransaction {
            def p = new CachedPerson(name: "Dave", age: 35).save()
            def id = p.id

            def p1 = CachedPerson.get(id)
            def p2 = CachedPerson.get(id)
            def p3 = CachedPerson.get(id)

            return [
                all: p.is(p1) && p1.is(p2) && p2.is(p3),
                p_p1: p.is(p1),
                p1_p2: p1.is(p2),
                p2_p3: p2.is(p3)
            ]
        }

        then: "all references point to the same instance"
        result.all == true
        result.p_p1 == true
        result.p1_p2 == true
        result.p2_p3 == true
    }
}

@Entity
class CachedPerson {
    String name
    Integer age
}
