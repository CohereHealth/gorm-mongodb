package example

import grails.testing.mixin.integration.Integration
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Ignore
import spock.lang.Specification

/**
 * Comprehensive integration test for @NativeTransactional annotation.
 * Tests all propagation levels, rollback scenarios, and interaction patterns.
 */
@Integration
class NativeTransactionalAnnotationIntegrationSpec extends Specification {

    @Autowired
    NativeTransactionalTestService testService

    void setup() {
        // Clean up before each test to ensure test isolation
        Provider.withNewSession {
            Provider.collection.drop()
            AuditEvent.collection.drop()
        }
    }

    // ========================================
    // Basic Service-Level Annotation Tests
    // ========================================

    void "test service-level @NativeTransactional - successful create"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "creating provider via service method"
        def provider = testService.createProviderWithServiceLevelNativeTransactional(
            firstName: "John",
            lastName: "Doe",
            age: 45
        )

        then: "provider is created and persisted"
        provider != null
        provider.id != null
        provider.firstName == "John"
        Provider.count() == initialCount + 1
    }

    void "test service-level @NativeTransactional - successful update"() {
        given: "existing provider"
        def provider = new Provider(firstName: "Jane", lastName: "Smith", age: 30).save(flush: true)

        when: "updating via service"
        def updated = testService.updateProviderWithServiceLevelNativeTransactional(provider.id, [age: 31])

        then: "update is persisted"
        updated.age == 31
        Provider.get(provider.id).age == 31
    }

    void "test service-level @NativeTransactional - rollback on exception"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "service method throws exception"
        Provider.withNativeTransaction {
            testService.createProviderWithServiceLevelNativeTransactional(firstName: "Fail", lastName: "Test", age: 25)
            throw new RuntimeException("Forced failure")
        }

        then: "exception is thrown and transaction rolled back"
        thrown(RuntimeException)
        Provider.count() == initialCount
    }

    // ========================================
    // Propagation.REQUIRED Tests
    // ========================================

    void "test REQUIRED propagation - creates transaction if none exists"() {
        when: "calling method with REQUIRED outside transaction"
        def provider = testService.createProviderWithPropagationRequired(
            firstName: "Required",
            lastName: "Test",
            age: 40
        )

        then: "new transaction created and committed"
        provider != null
        Provider.count() == 1
    }

    void "test REQUIRED propagation - joins existing transaction"() {
        when: "calling REQUIRED method inside existing transaction"
        def result = Provider.withNativeTransaction { session ->
            def p1 = testService.createProviderWithPropagationRequired(firstName: "First", lastName: "Provider", age: 30)
            def p2 = testService.createProviderWithPropagationRequired(firstName: "Second", lastName: "Provider", age: 35)

            return [
                count: Provider.count(),
                sessionId: session.getServerSession().getIdentifier()
            ]
        }

        then: "both providers created in same transaction"
        result.count == 2
        Provider.count() == 2
    }

    void "test REQUIRED propagation - rollback affects all operations"() {
        when: "outer transaction fails after REQUIRED method succeeds"
        Provider.withNativeTransaction {
            testService.createProviderWithPropagationRequired(firstName: "Will", lastName: "Rollback", age: 50)
            throw new RuntimeException("Outer transaction fails")
        }

        then: "exception thrown and ALL operations rolled back"
        thrown(RuntimeException)
        Provider.count() == 0
    }

    // ========================================
    // Propagation.REQUIRES_NEW Tests
    // ========================================

    void "test REQUIRES_NEW propagation - creates independent transaction"() {
        when: "calling REQUIRES_NEW method"
        def audit = testService.createAuditEventWithPropagationRequiresNew("test-entity-123", "TEST_ACTION")

        then: "audit created in independent transaction"
        audit != null
        audit.id != null
        AuditEvent.count() == 1
    }

    void "test REQUIRES_NEW propagation - independent commit despite outer rollback"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()
        def initialAuditCount = AuditEvent.count()

        when: "outer transaction fails after REQUIRES_NEW succeeds"
        Provider.withNativeTransaction {
            def provider = new Provider(firstName: "Outer", lastName: "Transaction", age: 40).save(flush: true)

            // This creates audit in INDEPENDENT transaction
            testService.createAuditEventWithPropagationRequiresNew(provider.id.toString(), "CREATE")

            // Outer transaction fails
            throw new RuntimeException("Outer transaction rollback")
        }

        then: "exception thrown"
        thrown(RuntimeException)

        and: "provider rolled back"
        Provider.count() == initialProviderCount

        and: "audit COMMITTED independently"
        AuditEvent.count() == initialAuditCount + 1
    }

    void "test programmatic transactions join by default - same session ID"() {
        when: "capturing session IDs from nested programmatic transactions"
        def sessionIds = []

        Provider.withNativeTransaction { outerSession ->
            sessionIds << outerSession.getServerSession().getIdentifier().toString()

            AuditEvent.withNativeTransaction { innerSession ->
                testService.createAuditEventWithPropagationRequiresNew("entity-id", "ACTION")
                sessionIds << innerSession.getServerSession().getIdentifier().toString()
                return null
            }

            return null
        }

        then: "programmatic transactions join existing transaction - same session ID"
        sessionIds.size() == 2
        sessionIds[0] == sessionIds[1]

        and: "REQUIRES_NEW created independent transaction internally and audit saved"
        AuditEvent.count() == 1
    }

    void "test REQUIRES_NEW within nested programmatic transactions - audit commits, outer aborts"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()
        def initialAuditCount = AuditEvent.count()

        when: "outer programmatic transaction contains nested programmatic transaction with REQUIRES_NEW that fails"
        Provider.withNativeTransaction {
            def p = new Provider(firstName: "Outer", lastName: "Success", age: 35).save(flush: true)

            try {
                AuditEvent.withNativeTransaction {
                    // This REQUIRES_NEW commits independently
                    testService.createAuditEventWithPropagationRequiresNew("test-id", "ACTION")
                    throw new RuntimeException("Inner programmatic transaction fails")
                }
            } catch (RuntimeException e) {
                // Exception caught but outer transaction will still fail
            }

            return p
        }

        then: "outer transaction fails with IllegalStateException due to aborted session"
        thrown(IllegalStateException)

        and: "REQUIRES_NEW audit committed independently"
        AuditEvent.count() == initialAuditCount + 1

        and: "outer provider rolled back"
        Provider.count() == initialProviderCount
    }

    // ========================================
    // Mixed Propagation Scenario Tests
    // ========================================

    void "test mixed propagation - provider with independent audit"() {
        when: "creating provider with independent audit"
        def result = testService.createProviderWithIndependentAuditRequiresNew(
            firstName: "Mixed",
            lastName: "Provider",
            age: 42
        )

        then: "both provider and audit created"
        result.provider != null
        result.audit != null
        Provider.count() == 1
        AuditEvent.count() == 1
        result.audit.entityId == result.provider.id.toString()
        result.audit.action == "CREATE"
    }

    void "test Spring AOP limitation - internal method calls bypass proxy"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()
        def initialAuditCount = AuditEvent.count()

        when: "calling service method that internally calls REQUIRES_NEW method"
        try {
            // This method is @NativeTransactional and internally calls
            // createAuditEventWithPropagationRequiresNew (also @NativeTransactional REQUIRES_NEW)
            // The internal call bypasses Spring proxy, so REQUIRES_NEW is not applied
            testService.createProviderWithAuditThenFailDemonstratingRollback(
                firstName: "Internal",
                lastName: "Call",
                age: 50
            )
        } catch (RuntimeException e) {
            // Expected exception
        }

        then: "both operations rolled back - internal call didn't create independent transaction"
        Provider.count() == initialProviderCount
        AuditEvent.count() == initialAuditCount
    }

    // ========================================
    // Nested Transaction Tests
    // ========================================

    void "test nested @NativeTransactional calls - same transaction context"() {
        when: "nested service calls"
        def sessionIds = []

        Provider.withNativeTransaction { outerSession ->
            sessionIds << outerSession.getServerSession().getIdentifier().toString()

            // Service method with service-level @NativeTransactional (REQUIRED)
            testService.createProviderWithServiceLevelNativeTransactional(firstName: "Nested", lastName: "Test", age: 33)

            // Capture session ID after service call
            sessionIds << outerSession.getServerSession().getIdentifier().toString()

            return null
        }

        then: "same session used (REQUIRED joins existing)"
        sessionIds[0] == sessionIds[1]
        Provider.count() == 1
    }

    void "test deeply nested @NativeTransactional - multiple levels"() {
        when: "multiple nested service calls"
        Provider.withNativeTransaction {
            // Level 1: service-level @NativeTransactional (REQUIRED)
            def provider = testService.createProviderWithServiceLevelNativeTransactional(firstName: "Level1", lastName: "Test", age: 30)

            // Level 2: method-level @NativeTransactional(REQUIRED)
            testService.createProviderWithPropagationRequired(firstName: "Level2", lastName: "Test", age: 31)

            // Level 3: independent transaction (REQUIRES_NEW)
            testService.createAuditEventWithPropagationRequiresNew(provider.id.toString(), "MULTILEVEL")

            return null
        }

        then: "all operations complete successfully"
        Provider.count() == 2
        AuditEvent.count() == 1
    }

    // ========================================
    // Concurrent Modification & Optimistic Locking Tests
    // ========================================

    void "test optimistic locking within @NativeTransactional"() {
        given: "existing provider with version"
        def provider = new Provider(firstName: "Version", lastName: "Test", age: 40).save(flush: true)
        def originalVersion = provider.version

        when: "updating via service (which increments version)"
        def updated = testService.updateProviderWithServiceLevelNativeTransactional(provider.id, [age: 41])

        then: "version incremented"
        updated.version == originalVersion + 1

        when: "attempting to save with stale version"
        provider.age = 42
        provider.version = originalVersion // Use old version
        provider.save(flush: true, failOnError: true)

        then: "optimistic locking exception thrown"
        thrown(Exception)
    }

    // ========================================
    // Transaction Attribute Tests
    // ========================================

    void "test transaction isolation - changes visible within transaction"() {
        when: "making changes within transaction"
        def results = Provider.withNativeTransaction {
            def p1 = testService.createProviderWithServiceLevelNativeTransactional(firstName: "First", lastName: "Provider", age: 30)
            def countDuringTx = Provider.count()
            def readBack = Provider.get(p1.id)

            return [
                countDuringTx: countDuringTx,
                canReadBack: readBack != null,
                readBackName: readBack?.firstName
            ]
        }

        then: "changes visible within same transaction"
        results.countDuringTx == 1
        results.canReadBack == true
        results.readBackName == "First"
    }

    void "test rollback on runtime exception"() {
        when: "service method throws RuntimeException"
        Provider.withNativeTransaction {
            testService.createProviderWithServiceLevelNativeTransactional(firstName: "Should", lastName: "Rollback", age: 25)
            testService.createProviderWithServiceLevelNativeTransactional(firstName: "Also", lastName: "Rollback", age: 26)
            throw new RuntimeException("Intentional failure")
        }

        then: "all operations rolled back"
        thrown(RuntimeException)
        Provider.count() == 0
    }

    void "test commit on successful completion"() {
        when: "service methods complete successfully"
        Provider.withNativeTransaction {
            testService.createProviderWithServiceLevelNativeTransactional(firstName: "Should", lastName: "Commit", age: 30)
            testService.createProviderWithServiceLevelNativeTransactional(firstName: "Also", lastName: "Commit", age: 31)
            // No exception - should commit
        }

        then: "all operations committed"
        Provider.count() == 2
        Provider.findByFirstName("Should") != null
        Provider.findByFirstName("Also") != null
    }

    // ========================================
    // Service Composition Tests
    // ========================================

    void "test service calling other services - transaction propagation"() {
        when: "service method calls other service methods"
        def result = Provider.withNativeTransaction {
            // Service-level @NativeTransactional (REQUIRED)
            def provider = testService.createProviderWithServiceLevelNativeTransactional(firstName: "Composed", lastName: "Test", age: 40)

            // Update using same service (REQUIRED - joins)
            testService.updateProviderWithServiceLevelNativeTransactional(provider.id, [age: 41])

            // Create audit using REQUIRES_NEW (independent)
            testService.createAuditEventWithPropagationRequiresNew(provider.id.toString(), "UPDATE")

            return Provider.get(provider.id)
        }

        then: "all operations complete with correct propagation"
        result.age == 41
        Provider.count() == 1
        AuditEvent.count() == 1
    }

    void "test service composition with partial rollback"() {
        given: "initial counts"
        def initialAuditCount = AuditEvent.count()

        when: "service methods with mixed outcomes"
        Provider.withNativeTransaction {
            def provider = testService.createProviderWithServiceLevelNativeTransactional(firstName: "Partial", lastName: "Rollback", age: 35)

            // This succeeds in independent transaction
            testService.createAuditEventWithPropagationRequiresNew(provider.id.toString(), "BEFORE_FAILURE")

            // Outer transaction fails
            throw new RuntimeException("Outer fails")
        }

        then: "outer rolled back, independent committed"
        thrown(RuntimeException)
        Provider.count() == 0
        AuditEvent.count() == initialAuditCount + 1
    }

    // ========================================
    // Edge Cases & Error Scenarios
    // ========================================

    void "test @NativeTransactional with null parameters"() {
        when: "passing null to service method"
        testService.updateProviderWithServiceLevelNativeTransactional(null, [age: 30])

        then: "appropriate exception thrown"
        thrown(Exception)
    }

    void "test @NativeTransactional with validation failure"() {
        when: "creating provider with invalid data"
        Provider.withNativeTransaction {
            testService.createProviderWithServiceLevelNativeTransactional(
                firstName: "", // Invalid: blank not allowed
                lastName: "Test",
                age: 30
            )
        }

        then: "validation exception and rollback"
        thrown(Exception)
        Provider.count() == 0
    }

    void "test multiple REQUIRES_NEW in sequence"() {
        when: "multiple independent transactions in sequence"
        Provider.withNativeTransaction {
            testService.createAuditEventWithPropagationRequiresNew("entity-1", "ACTION_1")
            testService.createAuditEventWithPropagationRequiresNew("entity-2", "ACTION_2")
            testService.createAuditEventWithPropagationRequiresNew("entity-3", "ACTION_3")

            // Outer transaction fails
            throw new RuntimeException("Outer fails")
        }

        then: "all independent transactions committed despite outer failure"
        thrown(RuntimeException)
        AuditEvent.count() == 3
        AuditEvent.findByEntityId("entity-1") != null
        AuditEvent.findByEntityId("entity-2") != null
        AuditEvent.findByEntityId("entity-3") != null
    }

    void "test empty transaction"() {
        when: "transaction with no operations"
        Provider.withNativeTransaction {
            // No operations
            return "empty"
        }

        then: "no errors, transaction commits"
        notThrown(Exception)
    }

    // ========================================
    // Performance & Session Management Tests
    // ========================================

    void "test bulk operations within @NativeTransactional"() {
        when: "creating multiple providers in single transaction"
        Provider.withNativeTransaction {
            (1..50).each { i ->
                testService.createProviderWithServiceLevelNativeTransactional(
                    firstName: "Bulk${i}",
                    lastName: "Provider",
                    age: 30 + i
                )
            }
        }

        then: "all providers created atomically"
        Provider.count() == 50
        Provider.findAllByLastName("Provider").size() == 50
    }

    void "test session cleanup after @NativeTransactional"() {
        when: "multiple sequential transactions"
        5.times { i ->
            Provider.withNativeTransaction {
                testService.createProviderWithServiceLevelNativeTransactional(
                    firstName: "Session${i}",
                    lastName: "Test",
                    age: 25
                )
            }
        }

        then: "all transactions complete successfully"
        Provider.count() == 5

        and: "no session leaks - each provider created"
        (0..4).each { i ->
            assert Provider.findByFirstName("Session${i}") != null
        }
    }
}
