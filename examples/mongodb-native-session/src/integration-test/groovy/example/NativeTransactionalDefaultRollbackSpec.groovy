package example

import grails.testing.mixin.integration.Integration
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Specification

/**
 * Integration tests for @NativeTransactional default rollback behavior.
 *
 * These tests verify that @NativeTransactional behaves like Spring's standard @Transactional
 * when NO explicit rollbackFor/noRollbackFor rules are specified:
 *
 * - Should rollback on RuntimeException and Error
 * - Should commit on checked exceptions (e.g., IOException)
 * - Should commit on successful completion
 *
 * This is the most common use case and MUST work correctly for the annotation to be production-ready.
 */
@Integration
class NativeTransactionalDefaultRollbackSpec extends Specification {

    @Autowired
    NativeTransactionalTestService testService

    void setup() {
        // Clean up before each test
        Provider.withNewSession {
            Provider.collection.drop()
            AuditEvent.collection.drop()
        }
    }

    // ========================================
    // Default Rollback on RuntimeException
    // ========================================

    void "test default behavior - rolls back on RuntimeException"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "service method throws RuntimeException WITHOUT explicit rollbackFor"
        testService.createProviderWithDefaultRollbackOnRuntimeException(
            firstName: "DefaultRollback",
            lastName: "Test",
            age: 30
        )

        then: "RuntimeException thrown and transaction rolled back (Spring's default)"
        thrown(RuntimeException)
        Provider.count() == initialCount
    }

    void "test default behavior - commits on success"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "service method completes successfully"
        def provider = testService.createProviderWithDefaultBehaviorSuccess(
            firstName: "Success",
            lastName: "Test",
            age: 30
        )

        then: "provider created and committed"
        provider != null
        provider.id != null
        Provider.count() == initialCount + 1
        Provider.findByFirstName("Success") != null
    }

    void "test default behavior - commits on checked exception (IOException)"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "checked exception thrown (IOException) - Spring default does NOT rollback"
        testService.createProviderWithDefaultBehaviorCheckedException(
            [firstName: "Checked", lastName: "Exception", age: 35],
            true
        )

        then: "IOException thrown but transaction COMMITS (Spring's default for checked exceptions)"
        thrown(IOException)
        Provider.count() == initialCount + 1
        Provider.findByFirstName("Checked") != null
    }

    void "test default behavior - rolls back on Error"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "Error thrown (AssertionError) - should rollback by default"
        testService.createProviderWithDefaultRollbackOnError(
            firstName: "ErrorTest",
            lastName: "Test",
            age: 40
        )

        then: "Error thrown and transaction rolled back"
        thrown(AssertionError)
        Provider.count() == initialCount
    }

    // ========================================
    // Default Rollback with Different Propagation Levels
    // ========================================

    void "test default rollback with REQUIRED propagation"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "REQUIRED method throws RuntimeException without explicit rollbackFor"
        testService.createProviderWithRequiredAndDefaultRollback(
            firstName: "Required",
            lastName: "Test",
            age: 45
        )

        then: "rolls back by default"
        thrown(RuntimeException)
        Provider.count() == initialCount
    }

    void "test default rollback with REQUIRES_NEW propagation"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()

        when: "REQUIRES_NEW method throws RuntimeException without explicit rollbackFor"
        Provider.withNativeTransaction {
            def provider = new Provider(firstName: "Outer", lastName: "Transaction", age: 50).save(failOnError: true)

            // Independent transaction - should rollback by default
            testService.createProviderWithRequiresNewAndDefaultRollback(
                firstName: "Independent",
                lastName: "Test",
                age: 51
            )
        }

        then: "inner transaction rolls back by default, outer also fails"
        thrown(RuntimeException)
        Provider.count() == initialProviderCount
    }

    // ========================================
    // Default Rollback in Complex Scenarios
    // ========================================

    void "test default rollback within nested transactions"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "outer transaction with inner default rollback"
        Provider.withNativeTransaction {
            // Create provider in outer transaction
            def outer = new Provider(firstName: "Outer", lastName: "Test", age: 60).save(failOnError: true)

            // Call service method that throws RuntimeException (default rollback)
            testService.createProviderWithDefaultRollbackOnRuntimeException(
                firstName: "Inner",
                lastName: "Test",
                age: 61
            )
        }

        then: "both operations rolled back"
        thrown(RuntimeException)
        Provider.count() == initialCount
        Provider.findByFirstName("Outer") == null
        Provider.findByFirstName("Inner") == null
    }

    void "test default rollback only affects failing transaction with REQUIRES_NEW"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()

        when: "outer transaction with inner REQUIRES_NEW that fails"
        Provider.withNativeTransaction {
            def outer = new Provider(firstName: "Outer", lastName: "Success", age: 70).save(failOnError: true)

            try {
                // Independent transaction with default rollback
                testService.createProviderWithRequiresNewAndDefaultRollback(
                    firstName: "IndependentFail",
                    lastName: "Test",
                    age: 71
                )
            } catch (RuntimeException e) {
                // Catch inner exception so outer can complete
            }

            return outer
        }

        then: "outer commits, inner rolls back"
        Provider.count() == initialProviderCount + 1
        Provider.findByFirstName("Outer") != null
        Provider.findByFirstName("IndependentFail") == null
    }

    // ========================================
    // Comparison Tests: Default vs Explicit
    // ========================================

    void "test default rollback matches explicit rollbackFor=[RuntimeException]"() {
        given: "two test scenarios"
        def initialCount = Provider.count()

        when: "using default rollback (no rollbackFor)"
        try {
            testService.createProviderWithDefaultRollbackOnRuntimeException(
                firstName: "Default",
                lastName: "Test",
                age: 80
            )
        } catch (RuntimeException e) {
            // Expected
        }
        def countAfterDefault = Provider.count()

        then: "rolled back"
        countAfterDefault == initialCount

        when: "using explicit rollbackFor=[RuntimeException]"
        try {
            testService.createProviderWithRollbackForAllExceptions(
                [firstName: "Explicit", lastName: "Test", age: 81],
                true
            )
        } catch (Exception e) {
            // Expected
        }
        def countAfterExplicit = Provider.count()

        then: "also rolled back - behavior matches"
        countAfterExplicit == initialCount
    }

    // ========================================
    // Edge Cases
    // ========================================

    void "test default rollback with NullPointerException"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "NPE thrown (unchecked exception)"
        Provider.withNativeTransaction {
            def provider = new Provider(firstName: "NPE", lastName: "Test", age: 90).save(failOnError: true)
            throw new NullPointerException("Simulated NPE")
        }

        then: "rolls back by default (NPE extends RuntimeException)"
        thrown(NullPointerException)
        Provider.count() == initialCount
    }

    void "test default rollback with custom RuntimeException subclass"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "custom RuntimeException subclass thrown"
        Provider.withNativeTransaction {
            def provider = new Provider(firstName: "Custom", lastName: "Test", age: 95).save(failOnError: true)
            throw new CustomRuntimeException("Custom exception")
        }

        then: "rolls back by default (subclass of RuntimeException)"
        thrown(CustomRuntimeException)
        Provider.count() == initialCount
    }

    void "test multiple operations roll back together on default behavior"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()
        def initialAuditCount = AuditEvent.count()

        when: "multiple operations in one transaction with default rollback"
        Provider.withNativeTransaction {
            def p1 = new Provider(firstName: "First", lastName: "Test", age: 100).save(failOnError: true)
            def p2 = new Provider(firstName: "Second", lastName: "Test", age: 101).save(failOnError: true)
            def audit = new AuditEvent(
                entityId: p1.id.toString(),
                entityType: "Provider",
                action: "CREATE",
                performedBy: "SYSTEM",
                timestamp: new Date()
            ).save(failOnError: true)

            // Throw exception - should rollback all operations
            throw new RuntimeException("All should rollback")
        }

        then: "all operations rolled back"
        thrown(RuntimeException)
        Provider.count() == initialProviderCount
        AuditEvent.count() == initialAuditCount
    }
}

/**
 * Custom RuntimeException subclass for testing.
 */
class CustomRuntimeException extends RuntimeException {
    CustomRuntimeException(String message) {
        super(message)
    }
}
