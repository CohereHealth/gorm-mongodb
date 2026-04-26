package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.grails.datastore.mapping.core.OptimisticLockingException

/**
 * Tests version conflict detection when working with versioned entities.
 *
 * With proper session caching, the version tracker should be unnecessary because:
 * - save() updates cache with the entity instance
 * - get() returns the SAME cached instance
 * - Updating the same instance naturally has the correct version
 *
 * This test verifies that the session cache prevents version conflicts that
 * the version tracker was designed to catch.
 */
class MongoVersionConflictSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [VersionedProduct]
    }

    def "test no version conflict when using cached instance"() {
        when: "save and update the same entity via cache"
        def result = VersionedProduct.withNativeTransaction {
            def p1 = new VersionedProduct(name: "Laptop", price: 1000).save()
            def id = p1.id
            println "After save: id=$id, version=${p1.version}"

            // Get returns SAME instance from cache
            def p2 = VersionedProduct.get(id)
            println "After get: sameInstance=${p1.is(p2)}, version=${p2.version}"

            // Update - should work because p2 IS p1
            p2.price = 1100
            p2.save()
            println "After update: version=${p2.version}"

            return [
                sameInstance: p1.is(p2),
                finalVersion: p2.version,
                finalPrice: p2.price
            ]
        }

        then: "no optimistic locking exception"
        result.sameInstance == true
        result.finalVersion == 1  // Version incremented from 0 to 1
        result.finalPrice == 1100
    }

    def "test version conflict prevented by session cache"() {
        when: "save and get returns same instance"
        def result = VersionedProduct.withNativeTransaction {
            // Save first instance
            def p1 = new VersionedProduct(name: "Phone", price: 500).save()
            def id = p1.id
            def initialVersion = p1.version

            // Get returns SAME instance from cache (not a new object)
            def p2 = VersionedProduct.get(id)

            // Update works because p2 IS p1 (same reference)
            p2.price = 600
            p2.save()

            return [
                sameInstance: p1.is(p2),
                initialVersion: initialVersion,
                finalVersion: p2.version,
                noException: true
            ]
        }

        then: "cache prevents version conflicts"
        result.sameInstance == true
        result.finalVersion > result.initialVersion
        result.noException == true
    }

    def "test multiple updates on same cached instance"() {
        when: "perform multiple updates"
        def versions = VersionedProduct.withNativeTransaction {
            def product = new VersionedProduct(name: "Tablet", price: 300).save()
            def v0 = product.version

            product.price = 320
            product.save()
            def v1 = product.version

            product.price = 340
            product.save()
            def v2 = product.version

            product.price = 360
            product.save()
            def v3 = product.version

            [v0: v0, v1: v1, v2: v2, v3: v3]
        }

        then: "versions increment correctly"
        versions.v0 == 0
        versions.v1 == 1
        versions.v2 == 2
        versions.v3 == 3
    }

    def "test cache returns same instance across multiple gets"() {
        when: "get same entity multiple times and update"
        def result = VersionedProduct.withNativeTransaction {
            def original = new VersionedProduct(name: "Mouse", price: 50).save()
            def id = original.id

            // Multiple gets - all should return same instance
            def get1 = VersionedProduct.get(id)
            def get2 = VersionedProduct.get(id)
            def get3 = VersionedProduct.get(id)

            // Update via one of them
            get2.price = 55
            get2.save()

            return [
                allSame: original.is(get1) && get1.is(get2) && get2.is(get3),
                allHaveNewVersion: (original.version == 1 && get1.version == 1 &&
                                   get2.version == 1 && get3.version == 1),
                allHaveNewPrice: (original.price == 55 && get1.price == 55 &&
                                 get2.price == 55 && get3.price == 55)
            ]
        }

        then: "all references point to same updated instance"
        result.allSame == true
        result.allHaveNewVersion == true
        result.allHaveNewPrice == true
    }
}

@Entity
class VersionedProduct {
    String name
    BigDecimal price
    Long version  // Explicit version field for optimistic locking
}
