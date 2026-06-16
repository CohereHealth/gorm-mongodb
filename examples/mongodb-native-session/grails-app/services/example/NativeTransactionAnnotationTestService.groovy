package example

import org.grails.datastore.mapping.mongo.NativeTransactional
import org.springframework.transaction.annotation.Propagation

/**
 * Simple service for testing @NativeTransactional annotation.
 * Tests basic annotation functionality with minimal complexity.
 */
class NativeTransactionAnnotationTestService {

    /**
     * Basic @NativeTransactional - default REQUIRED propagation.
     * Should commit on success.
     */
    @NativeTransactional
    String createWithRequired(String name, String description) {
        def tag = new Tag(name: name, description: description).save(failOnError: true)
        return tag.id
    }

    /**
     * @NativeTransactional with REQUIRES_NEW propagation.
     * Creates independent transaction even if called from within another transaction.
     */
    @NativeTransactional(propagation = Propagation.REQUIRES_NEW)
    String createWithRequiresNew(String name, String description) {
        def tag = new Tag(name: name, description: description).save(failOnError: true)
        return tag.id
    }

    /**
     * @NativeTransactional that throws exception - should rollback.
     */
    @NativeTransactional
    void createAndFail(String name, String description) {
        new Tag(name: name, description: description).save(failOnError: true)
        throw new RuntimeException("Intentional failure for rollback test")
    }

    /**
     * @NativeTransactional with REQUIRES_NEW that throws exception.
     * Should rollback only this transaction, not outer transaction.
     */
    @NativeTransactional(propagation = Propagation.REQUIRES_NEW)
    void createWithRequiresNewAndFail(String name, String description) {
        new Tag(name: name, description: description).save(failOnError: true)
        throw new RuntimeException("REQUIRES_NEW transaction fails")
    }

    /**
     * @NativeTransactional with rollbackFor specific exception.
     * Should rollback on CustomTestException.
     */
    @NativeTransactional(rollbackFor = [CustomTestException])
    void createWithRollbackFor(String name, String description) {
        new Tag(name: name, description: description).save(failOnError: true)
        throw new CustomTestException("Should trigger rollback")
    }

    /**
     * @NativeTransactional with noRollbackFor specific exception.
     * Should NOT rollback on CustomTestException - changes should be committed.
     */
    @NativeTransactional(noRollbackFor = [CustomTestException])
    void createWithNoRollbackFor(String name, String description) {
        new Tag(name: name, description: description).save(failOnError: true)
        throw new CustomTestException("Should NOT trigger rollback")
    }
}

/**
 * Custom exception for testing rollbackFor and noRollbackFor configurations.
 */
class CustomTestException extends RuntimeException {
    CustomTestException(String message) {
        super(message)
    }
}
