package example

import grails.testing.mixin.integration.Integration
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Specification

/**
 * Simple integration tests for @NativeTransactional annotation.
 *
 * No @NativeRollback annotation - we want to test actual commit/rollback behavior
 * of the @NativeTransactional annotation without interference from outer test transactions.
 *
 * Focuses on core functionality:
 * - REQUIRED propagation (default) - commit and rollback
 * - REQUIRES_NEW propagation - independent transactions
 * - rollbackFor configuration
 */
@Integration
class NativeTransactionAnnotationSpec extends Specification {

    @Autowired
    NativeTransactionAnnotationTestService annotationTestService

    void setup() {
        // Manual cleanup before each test since we're not using @NativeRollback
        Tag.collection.drop()
    }

    void cleanup() {
        // Manual cleanup after each test since we're not using @NativeRollback
        Tag.collection.drop()
    }

    // ========================================
    // REQUIRED Propagation Tests (Default)
    // ========================================

    void "test @NativeTransactional REQUIRED - successful commit"() {
        given: "initial count"
        def initialCount = Tag.count()

        when: "calling annotated method with REQUIRED (default)"
        def id = annotationTestService.createWithRequired("test-tag", "Test description")

        then: "tag is persisted"
        id != null
        Tag.count() == initialCount + 1
        Tag.findByName("test-tag") != null
    }

    void "test @NativeTransactional REQUIRED - rollback on exception"() {
        given: "initial count"
        def initialCount = Tag.count()

        when: "calling annotated method that throws exception"
        annotationTestService.createAndFail("fail-tag", "Will be rolled back")

        then: "exception thrown and changes rolled back"
        thrown(RuntimeException)
        Tag.count() == initialCount
        Tag.findByName("fail-tag") == null
    }

    void "test @NativeTransactional REQUIRED - joins outer programmatic transaction"() {
        given: "initial count"
        def initialCount = Tag.count()

        when: "programmatic transaction calls annotated method which joins it"
        Tag.withNativeTransaction {
            new Tag(name: "programmatic-tag", description: "Programmatic").save(failOnError: true)

            // Annotation joins programmatic transaction
            annotationTestService.createWithRequired("annotation-tag", "Joins programmatic")

            // Fail outer transaction
            throw new RuntimeException("Programmatic transaction fails")
        }

        then: "exception thrown and BOTH tags rolled back together"
        thrown(RuntimeException)
        Tag.count() == initialCount
        Tag.findByName("programmatic-tag") == null
        Tag.findByName("annotation-tag") == null
    }

    // ========================================
    // REQUIRES_NEW Propagation Tests
    // ========================================

    void "test @NativeTransactional REQUIRES_NEW - creates independent transaction"() {
        given: "initial count"
        def initialCount = Tag.count()

        when: "calling annotated method with REQUIRES_NEW"
        def id = annotationTestService.createWithRequiresNew("independent-tag", "Independent transaction")

        then: "tag is persisted in independent transaction"
        id != null
        Tag.count() == initialCount + 1
        Tag.findByName("independent-tag") != null
    }

    void "test @NativeTransactional REQUIRES_NEW - rollback only inner transaction"() {
        given: "initial count"
        def initialCount = Tag.count()

        when: "outer transaction succeeds but inner REQUIRES_NEW fails"
        Tag.withNativeTransaction {
            // Outer transaction - create tag
            new Tag(name: "outer-tag", description: "Outer transaction").save(failOnError: true)

            try {
                // Inner REQUIRES_NEW transaction - fails
                annotationTestService.createWithRequiresNewAndFail("inner-tag", "Will be rolled back")
            } catch (RuntimeException e) {
                // Catch exception so outer transaction can continue
                println "Caught exception from inner transaction: ${e.message}"
            }

            // Outer transaction continues
        }

        then: "outer tag committed, inner tag rolled back"
        Tag.count() == initialCount + 1
        Tag.findByName("outer-tag") != null
        Tag.findByName("inner-tag") == null
    }

    // ========================================
    // rollbackFor Configuration Test
    // ========================================

    void "test @NativeTransactional rollbackFor - RuntimeException always rolls back"() {
        given: "initial count"
        def initialCount = Tag.count()

        when: "calling method that throws RuntimeException"
        annotationTestService.createAndFail("rollback-tag", "Should rollback on RuntimeException")

        then: "RuntimeException triggers rollback (default behavior)"
        thrown(RuntimeException)
        Tag.count() == initialCount
        Tag.findByName("rollback-tag") == null
    }

    void "test @NativeTransactional rollbackFor - custom exception DOES rollback"() {
        given: "initial count"
        def initialCount = Tag.count()

        when: "calling method with rollbackFor CustomTestException"
        annotationTestService.createWithRollbackFor("rollback-for-tag", "Should rollback")

        then: "exception thrown and changes rolled back"
        def e = thrown(CustomTestException)
        e.message == "Should trigger rollback"
        Tag.count() == initialCount
        Tag.findByName("rollback-for-tag") == null
    }

    void "test @NativeTransactional noRollbackFor - custom exception does NOT rollback"() {
        given: "initial count"
        def initialCount = Tag.count()

        when: "calling method with noRollbackFor CustomTestException"
        annotationTestService.createWithNoRollbackFor("no-rollback-for-tag", "Should commit")

        then: "exception thrown but changes committed"
        def e = thrown(CustomTestException)
        e.message == "Should NOT trigger rollback"
        Tag.count() == initialCount + 1
        Tag.findByName("no-rollback-for-tag") != null
    }
}
