package example

import grails.testing.mixin.integration.Integration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.TransactionTimedOutException
import spock.lang.Specification
import spock.lang.Ignore

/**
 * Detailed integration tests for each @NativeTransactional annotation parameter.
 * Tests every parameter individually and in combination.
 */
@Integration
class NativeTransactionalParametersIntegrationSpec extends Specification {

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
    // PROPAGATION Parameter Tests
    // ========================================

    void "test propagation REQUIRED - creates transaction if none exists"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "calling REQUIRED method outside transaction"
        def provider = testService.createProviderWithPropagationRequired(firstName: "Required", lastName: "Test", age: 30)

        then: "transaction created and provider persisted"
        provider != null
        Provider.count() == initialCount + 1
    }

    void "test propagation REQUIRED - joins existing transaction"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "calling REQUIRED inside existing transaction"
        def result = Provider.withNativeTransaction {
            def p1 = testService.createProviderWithPropagationRequired(firstName: "First", lastName: "Provider", age: 30)
            def p2 = testService.createProviderWithPropagationRequired(firstName: "Second", lastName: "Provider", age: 35)
            throw new RuntimeException("Rollback both")
        }

        then: "both rolled back together"
        thrown(RuntimeException)
        Provider.count() == initialCount
    }

    void "test propagation REQUIRES_NEW - creates independent transaction"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "calling REQUIRES_NEW inside transaction that fails"
        Provider.withNativeTransaction {
            testService.createProviderWithPropagationRequiresNew(firstName: "Independent", lastName: "Transaction", age: 40)
            throw new RuntimeException("Outer fails")
        }

        then: "outer rolled back but REQUIRES_NEW committed"
        thrown(RuntimeException)
        Provider.count() == initialCount + 1
        Provider.findByFirstName("Independent") != null
    }

    void "test propagation MANDATORY - throws IllegalTransactionStateException"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "calling MANDATORY propagation without existing transaction"
        testService.createProviderWithPropagationMandatory(firstName: "Mandatory", lastName: "Test", age: 35)

        then: "IllegalTransactionStateException thrown - Spring checks MANDATORY before our validation"
        thrown(IllegalTransactionStateException)
        Provider.count() == initialCount
    }

    void "test propagation NESTED - throws UnsupportedOperationException"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "using NESTED propagation"
        testService.createProviderWithPropagationNested(firstName: "Nested", lastName: "Test", age: 50)

        then: "UnsupportedOperationException thrown - NESTED not supported in v1"
        thrown(UnsupportedOperationException)
        Provider.count() == initialCount
    }

    // ========================================
    // TIMEOUT Parameter Tests
    // ========================================

    void "test timeout parameter - successful within timeout"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "operation completes within timeout"
        def provider = testService.createProviderWithTimeout10Seconds(firstName: "FastOp", lastName: "Test", age: 30)

        then: "transaction succeeds"
        provider != null
        Provider.count() == initialCount + 1
    }


    void "test timeout parameter - timeout set correctly"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "calling method with timeout"
        def provider = testService.createProviderWithTimeout5Seconds(firstName: "Timeout", lastName: "Test", age: 32)

        then: "succeeds without hitting timeout"
        provider != null
        Provider.count() == initialCount + 1
    }

    // ========================================
    // ROLLBACKFOR Parameter Tests
    // ========================================

    void "test rollbackFor - rolls back on specified checked exception"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "IOException occurs (checked exception in rollbackFor)"
        testService.createProviderWithRollbackForIOException(
            [firstName: "RollbackIO", lastName: "Test", age: 40],
            true
        )

        then: "exception thrown and transaction rolled back"
        thrown(IOException)
        Provider.count() == initialCount
    }

    void "test rollbackFor - commits if exception not thrown"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "no exception occurs"
        def provider = testService.createProviderWithRollbackForIOException(
            [firstName: "NoException", lastName: "Test", age: 41],
            false
        )

        then: "transaction commits"
        provider != null
        Provider.count() == initialCount + 1
    }

    void "test rollbackFor - multiple exception types"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "IllegalArgumentException occurs"
        testService.createProviderWithRollbackForMultipleExceptions(
            [firstName: "MultiEx1", lastName: "Test", age: 42],
            "IllegalArgument"
        )

        then: "rolls back"
        thrown(IllegalArgumentException)
        Provider.count() == initialCount

        when: "IllegalStateException occurs"
        testService.createProviderWithRollbackForMultipleExceptions(
            [firstName: "MultiEx2", lastName: "Test", age: 43],
            "IllegalState"
        )

        then: "also rolls back"
        thrown(IllegalStateException)
        Provider.count() == initialCount
    }

    void "test rollbackFor - Exception.class catches all"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "any exception occurs"
        testService.createProviderWithRollbackForAllExceptions(
            [firstName: "AnyEx", lastName: "Test", age: 44],
            true
        )

        then: "rolls back"
        thrown(Exception)
        Provider.count() == initialCount
    }

    // ========================================
    // ROLLBACKFORCLASSNAME Parameter Tests
    // ========================================

    void "test rollbackForClassName - rolls back on specified exception class name"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "IOException occurs via className"
        testService.createProviderWithRollbackForClassNameIOException(
            [firstName: "ClassName1", lastName: "Test", age: 50],
            true
        )

        then: "rolls back"
        thrown(IOException)
        Provider.count() == initialCount
    }

    void "test rollbackForClassName - multiple class names"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "SQLException occurs"
        testService.createProviderWithRollbackForMultipleClassNames(
            [firstName: "SQL", lastName: "Test", age: 51],
            "SQL"
        )

        then: "rolls back"
        thrown(java.sql.SQLException)
        Provider.count() == initialCount

        when: "SocketException occurs"
        testService.createProviderWithRollbackForMultipleClassNames(
            [firstName: "Socket", lastName: "Test", age: 52],
            "Socket"
        )

        then: "also rolls back"
        thrown(java.net.SocketException)
        Provider.count() == initialCount
    }

    // ========================================
    // NOROLLBACKFOR Parameter Tests
    // ========================================

    void "test noRollbackFor - does NOT rollback on specified exception"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "BusinessException occurs (in noRollbackFor)"
        testService.createProviderWithNoRollbackForBusinessException(
            [firstName: "NoRollback", lastName: "Test", age: 60],
            true
        )

        then: "exception thrown but transaction COMMITS"
        thrown(BusinessException)
        Provider.count() == initialCount + 1
        Provider.findByFirstName("NoRollback") != null
    }

    void "test noRollbackFor - commits normally without exception"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "no exception occurs"
        def provider = testService.createProviderWithNoRollbackForBusinessException(
            [firstName: "Normal", lastName: "Test", age: 61],
            false
        )

        then: "commits normally"
        provider != null
        Provider.count() == initialCount + 1
    }

    void "test noRollbackFor - multiple exception types"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "BusinessException occurs"
        testService.createProviderWithNoRollbackForMultipleExceptions(
            [firstName: "Business", lastName: "Test", age: 62],
            "Business"
        )

        then: "does not rollback"
        thrown(BusinessException)
        Provider.count() == initialCount + 1

        when: "CustomValidationException occurs"
        testService.createProviderWithNoRollbackForMultipleExceptions(
            [firstName: "Validation", lastName: "Test", age: 63],
            "Validation"
        )

        then: "also does not rollback"
        thrown(CustomValidationException)
        Provider.count() == initialCount + 2
    }

    // ========================================
    // NOROLLBACKFORCLASSNAME Parameter Tests
    // ========================================

    void "test noRollbackForClassName - does NOT rollback on specified class name"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "BusinessException occurs via className"
        testService.createProviderWithNoRollbackForClassName(
            [firstName: "NoRollbackClass", lastName: "Test", age: 70],
            true
        )

        then: "exception thrown but commits"
        thrown(BusinessException)
        Provider.count() == initialCount + 1
    }

    void "test noRollbackForClassName - multiple class names"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "First exception type"
        testService.createProviderWithNoRollbackForMultipleClassNames(
            [firstName: "Class1", lastName: "Test", age: 71],
            "Business"
        )

        then: "does not rollback"
        thrown(BusinessException)
        Provider.count() == initialCount + 1

        when: "Second exception type"
        testService.createProviderWithNoRollbackForMultipleClassNames(
            [firstName: "Class2", lastName: "Test", age: 72],
            "Validation"
        )

        then: "also does not rollback"
        thrown(CustomValidationException)
        Provider.count() == initialCount + 2
    }

    // ========================================
    // COMBINED Parameters Tests
    // ========================================

    void "test combined parameters - all attributes together (success case)"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "using combined parameters successfully"
        def provider = testService.createProviderWithCombinedParameters(
            [firstName: "Combined", lastName: "Success", age: 80],
            "success"
        )

        then: "succeeds with all parameters applied"
        provider != null
        Provider.count() == initialCount + 1
    }

    void "test combined parameters - rollbackFor exception triggers rollback"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "IOException occurs (in rollbackFor)"
        testService.createProviderWithCombinedParameters(
            [firstName: "Combined", lastName: "Rollback", age: 81],
            "rollbackException"
        )

        then: "rolls back"
        thrown(IOException)
        Provider.count() == initialCount
    }

    void "test combined parameters - noRollbackFor exception commits"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "BusinessException occurs (in noRollbackFor)"
        testService.createProviderWithCombinedParameters(
            [firstName: "Combined", lastName: "NoRollback", age: 82],
            "noRollbackException"
        )

        then: "commits despite exception"
        thrown(BusinessException)
        Provider.count() == initialCount + 1
    }

    void "test combined parameters - noRollbackFor takes precedence over rollbackFor"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "exception is in both rollbackFor and noRollbackFor"
        testService.createProviderWithConflictingRules(
            [firstName: "Conflict", lastName: "Test", age: 83],
            "Business"
        )

        then: "noRollbackFor takes precedence - commits"
        thrown(BusinessException)
        Provider.count() == initialCount + 1
    }

    void "test combined parameters - regular exception still rolls back"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "RuntimeException occurs (not in noRollbackFor)"
        testService.createProviderWithConflictingRules(
            [firstName: "Runtime", lastName: "Test", age: 84],
            "Runtime"
        )

        then: "rolls back normally"
        thrown(RuntimeException)
        Provider.count() == initialCount
    }

    // ========================================
    // Default Rollback Behavior (No explicit rollbackFor)
    // ========================================

    void "test default rollback behavior - RuntimeException without rollbackFor"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "RuntimeException thrown with NO rollbackFor specified"
        testService.createProviderWithDefaultRollbackOnRuntimeException(
            firstName: "DefaultRollback",
            lastName: "Test",
            age: 85
        )

        then: "rolls back by default (Spring's standard behavior)"
        thrown(RuntimeException)
        Provider.count() == initialCount
    }

    void "test default behavior - checked exception does NOT rollback"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "checked exception (IOException) thrown with NO rollbackFor"
        testService.createProviderWithDefaultBehaviorCheckedException(
            [firstName: "CheckedException", lastName: "Test", age: 86],
            true
        )

        then: "checked exception commits by default (Spring's standard behavior)"
        thrown(IOException)
        Provider.count() == initialCount + 1
        Provider.findByFirstName("CheckedException") != null
    }

    void "test default behavior - successful completion commits"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "no exception thrown"
        def provider = testService.createProviderWithDefaultBehaviorSuccess(
            firstName: "DefaultSuccess",
            lastName: "Test",
            age: 87
        )

        then: "commits normally"
        provider != null
        Provider.count() == initialCount + 1
    }

    // ========================================
    // Mixed Annotation with Programmatic Transactions
    // ========================================

    void "test annotation with nested programmatic transaction - both succeed"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()
        def initialAuditCount = AuditEvent.count()

        when: "annotated method calls programmatic transaction"
        def result = testService.createProviderWithMixedDeclarativeAndProgrammaticTransactions(
            firstName: "Mixed1",
            lastName: "Test",
            age: 90
        )

        then: "both committed"
        result.provider != null
        result.audit != null
        Provider.count() == initialProviderCount + 1
        AuditEvent.count() == initialAuditCount + 1
    }

    void "test annotation with nested programmatic transaction - outer fails"() {
        given: "initial counts"
        def initialProviderCount = Provider.count()
        def initialAuditCount = AuditEvent.count()

        when: "annotated method fails after programmatic call"
        Provider.withNativeTransaction {
            testService.createProviderWithMixedDeclarativeAndProgrammaticTransactions(
                firstName: "Mixed2",
                lastName: "Test",
                age: 91
            )
            throw new RuntimeException("Outer fails")
        }

        then: "both rolled back (same transaction context)"
        thrown(RuntimeException)
        Provider.count() == initialProviderCount
        AuditEvent.count() == initialAuditCount
    }

    void "test programmatic transaction calls annotated method with REQUIRES_NEW"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "programmatic transaction calls REQUIRES_NEW annotated method"
        Provider.withNativeTransaction {
            def p1 = new Provider(firstName: "Programmatic", lastName: "Test", age: 92).save(failOnError: true)

            // This runs in independent transaction
            testService.createProviderInIndependentTransactionCalledFromProgrammatic(
                firstName: "Independent",
                lastName: "Test",
                age: 93
            )

            throw new RuntimeException("Programmatic fails")
        }

        then: "programmatic rolled back, annotated method committed"
        thrown(RuntimeException)
        Provider.count() == initialCount + 1
        Provider.findByFirstName("Independent") != null
        Provider.findByFirstName("Programmatic") == null
    }

    void "test programmatic transaction calls annotated method with REQUIRED"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "programmatic transaction calls REQUIRED annotated method"
        Provider.withNativeTransaction {
            def p1 = new Provider(firstName: "Programmatic2", lastName: "Test", age: 94).save(failOnError: true)

            // This joins existing transaction
            testService.createProviderWithPropagationRequired(firstName: "Joined", lastName: "Test", age: 95)

            throw new RuntimeException("Both fail")
        }

        then: "both rolled back together"
        thrown(RuntimeException)
        Provider.count() == initialCount
    }

    void "test multiple annotation calls from programmatic transaction"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "programmatic transaction calls multiple annotated methods"
        def results = Provider.withNativeTransaction {
            def p1 = testService.createProviderWithPropagationRequired(firstName: "First", lastName: "Method", age: 96)
            def p2 = testService.createProviderWithPropagationRequired(firstName: "Second", lastName: "Method", age: 97)
            def p3 = testService.createProviderWithPropagationRequiresNew(firstName: "Independent", lastName: "Method", age: 98)

            throw new RuntimeException("Main fails")
        }

        then: "REQUIRED rolled back, REQUIRES_NEW committed"
        thrown(RuntimeException)
        Provider.count() == initialCount + 1
        Provider.findByFirstName("Independent") != null
        Provider.findByFirstName("First") == null
        Provider.findByFirstName("Second") == null
    }

    void "test annotation calls programmatic which calls another annotation"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "deeply nested mixed transactions"
        def result = Provider.withNativeTransaction { // Level 1: programmatic
            testService.createProviderWithPropagationRequired(firstName: "L1", lastName: "Test", age: 100) // Level 2: annotation REQUIRED

            Provider.withNativeTransaction { // Level 3: programmatic nested
                testService.createProviderWithPropagationRequiresNew(firstName: "L3", lastName: "Test", age: 101) // Level 4: annotation REQUIRES_NEW
            }

            throw new RuntimeException("Level 1 fails")
        }

        then: "REQUIRES_NEW survives, others rolled back"
        thrown(RuntimeException)
        Provider.count() == initialCount + 1
        Provider.findByFirstName("L3") != null
        Provider.findByFirstName("L1") == null
    }

    // ========================================
    // Edge Cases
    // ========================================

    void "test annotation with validation failure rolls back"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "validation fails"
        testService.createProviderWithPropagationRequired(
            firstName: "", // Invalid: blank not allowed
            lastName: "Test",
            age: 30
        )

        then: "exception and rollback"
        thrown(Exception)
        Provider.count() == initialCount
    }

    void "test annotation with database constraint violation rolls back"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "creating provider with negative age"
        testService.createProviderWithPropagationRequired(
            firstName: "Invalid",
            lastName: "Age",
            age: -5 // Violates constraint
        )

        then: "exception and rollback"
        thrown(Exception)
        Provider.count() == initialCount
    }

    void "test multiple sequential annotated calls - all succeed"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "sequential calls to annotated methods"
        5.times { i ->
            testService.createProviderWithPropagationRequired(
                firstName: "Sequential${i}",
                lastName: "Test",
                age: 20 + i
            )
        }

        then: "all committed in separate transactions"
        Provider.count() == initialCount + 5
        (0..4).each { i ->
            assert Provider.findByFirstName("Sequential${i}") != null
        }
    }

    void "test annotation with null entity handling"() {
        given: "initial count"
        def initialCount = Provider.count()

        when: "attempting to save null"
        Provider.withNativeTransaction {
            testService.createProviderWithPropagationRequired([:]) // Missing required fields
        }

        then: "validation exception"
        thrown(Exception)
        Provider.count() == initialCount
    }
}
