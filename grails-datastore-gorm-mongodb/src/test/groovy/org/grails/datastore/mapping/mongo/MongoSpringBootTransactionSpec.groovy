package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec

/**
 * Tests verifying native transaction behavior with service-layer patterns.
 *
 * NOTE: @Transactional annotation testing requires full Spring Boot integration test
 * environment with AOP proxy setup. These tests focus on verifying native transaction
 * behavior with service layer patterns that can be tested in unit test environment.
 */
class MongoSpringBootTransactionSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Staff]
    }

    def "test withNativeTransaction with successful commit"() {
        when: "using withNativeTransaction for service operation"
        def result = Staff.withNativeTransaction {
            new Staff(name: "John", role: "Engineering").save()
        }

        then: "data is persisted"
        result != null
        result.name == "John"
        result.role == "Engineering"
        Staff.count() == 1
    }

    def "test withNativeTransaction with rollback on exception"() {
        given: "initial count"
        def initialCount = Staff.count()

        when: "transaction throws exception"
        try {
            Staff.withNativeTransaction {
                new Staff(name: "Jane", role: "Sales").save()
                throw new RuntimeException("Simulated error")
            }
        } catch (RuntimeException e) {
            // Expected - native transaction should rollback
        }

        then: "rollback prevents data persistence"
        Staff.count() == initialCount
    }

    def "test nested withNativeTransaction operations"() {
        when: "nested native transaction operations"
        def results = Staff.withNativeTransaction {
            // Create first staff
            def staff1 = new Staff(name: "Alice", role: "Engineering").save()

            // Nested operation in same transaction
            Staff.withNativeTransaction {
                def staff2 = new Staff(name: "Bob", role: "Marketing").save()
                return [staff1: staff1, staff2: staff2]
            }
        }

        then: "both operations committed in same transaction"
        results.staff1 != null
        results.staff2 != null
        Staff.count() == 2
        Staff.findByName("Alice") != null
        Staff.findByName("Bob") != null
    }

    def "test multiple entities in single native transaction"() {
        when: "multiple entities created in one transaction"
        Staff.withNativeTransaction {
            new Staff(name: "Charlie", role: "Sales").save()
            new Staff(name: "Diana", role: "Marketing").save()
            new Staff(name: "Eve", role: "HR").save()
        }

        then: "all entities persisted atomically"
        Staff.count() == 3
        Staff.findByName("Charlie") != null
        Staff.findByName("Diana") != null
        Staff.findByName("Eve") != null
    }

    def "test transaction isolation - rollback doesn't affect previous commits"() {
        given: "first transaction commits successfully"
        Staff.withNativeTransaction {
            new Staff(name: "Frank", role: "Engineering").save()
        }

        when: "second transaction fails"
        try {
            Staff.withNativeTransaction {
                new Staff(name: "Grace", role: "Sales").save()
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException ignored) {
            // Expected
        }

        then: "first transaction data survives, second rolled back"
        Staff.count() == 1
        Staff.findByName("Frank") != null
        Staff.findByName("Grace") == null
    }
}

@Entity
class Staff {
    String name
    String role
}