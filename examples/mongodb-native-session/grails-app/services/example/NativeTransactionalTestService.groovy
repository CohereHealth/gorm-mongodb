package example

import org.grails.datastore.mapping.mongo.NativeTransactional
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation

/**
 * Comprehensive service demonstrating all @NativeTransactional annotation features.
 *
 * <p>This service includes:</p>
 * <ul>
 *   <li>Basic CRUD operations with service-level annotation</li>
 *   <li>All propagation levels (REQUIRED, REQUIRES_NEW, SUPPORTS, etc.)</li>
 *   <li>Timeout configuration examples</li>
 *   <li>RollbackFor and NoRollbackFor exception handling</li>
 *   <li>Combined parameter usage</li>
 *   <li>Mixed declarative and programmatic transactions</li>
 * </ul>
 *
 * <p>Used by integration tests to verify @NativeTransactional behavior.</p>
 */
@Service
@NativeTransactional  // Service-level: Default for all methods is native transaction with REQUIRED propagation
class NativeTransactionalTestService {

    // ========================================
    // Basic CRUD Operations
    // ========================================

    /**
     * Creates a provider using the service-level @NativeTransactional (REQUIRED).
     */
    Provider createProviderWithServiceLevelNativeTransactional(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    /**
     * Updates a provider using the service-level @NativeTransactional (REQUIRED).
     */
    def updateProviderWithServiceLevelNativeTransactional(Serializable id, Map updates) {
        def provider = Provider.get(id)
        updates.each { key, value ->
            provider[key] = value
        }
        provider.save(failOnError: true)
        return provider
    }

    // ========================================
    // Propagation: REQUIRED
    // ========================================

    /**
     * Creates a provider with explicit REQUIRED propagation.
     * Joins existing transaction or creates new one if none exists.
     */
    @NativeTransactional(propagation = Propagation.REQUIRED)
    Provider createProviderWithPropagationRequired(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    // ========================================
    // Propagation: REQUIRES_NEW
    // ========================================

    /**
     * Creates an audit event with REQUIRES_NEW propagation.
     * Always creates a new independent transaction that commits
     * separately from the caller's transaction.
     */
    @NativeTransactional(propagation = Propagation.REQUIRES_NEW)
    AuditEvent createAuditEventWithPropagationRequiresNew(String entityId, String action) {
        return new AuditEvent(
            entityId: entityId,
            entityType: "Provider",
            action: action,
            performedBy: "SYSTEM",
            timestamp: new Date()
        ).save(failOnError: true)
    }

    @NativeTransactional(propagation = Propagation.REQUIRES_NEW)
    Provider createProviderWithPropagationRequiresNew(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    @NativeTransactional(propagation = Propagation.REQUIRES_NEW)
    Provider createProviderInIndependentTransactionCalledFromProgrammatic(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    // ========================================
    // Propagation: SUPPORTS
    // ========================================

    /**
     * SUPPORTS: Executes within a transaction if one exists, otherwise non-transactionally.
     */
    @NativeTransactional(propagation = Propagation.SUPPORTS)
    Provider createProviderWithPropagationSupports(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    // ========================================
    // Propagation: NOT_SUPPORTED
    // ========================================

    /**
     * NOT_SUPPORTED: Always executes outside a transaction, suspending any existing transaction.
     */
    @NativeTransactional(propagation = Propagation.NOT_SUPPORTED)
    Provider createProviderWithPropagationNotSupported(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    // ========================================
    // Propagation: MANDATORY
    // ========================================

    /**
     * MANDATORY: Must execute within an existing transaction, throws exception otherwise.
     */
    @NativeTransactional(propagation = Propagation.MANDATORY)
    Provider createProviderWithPropagationMandatory(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    // ========================================
    // Propagation: NEVER
    // ========================================

    /**
     * NEVER: Must execute outside a transaction, throws exception if one exists.
     */
    @NativeTransactional(propagation = Propagation.NEVER)
    Provider createProviderWithPropagationNever(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    // ========================================
    // Propagation: NESTED
    // ========================================

    /**
     * NESTED: Creates a nested transaction (savepoint).
     * Note: May not be fully supported by MongoDB native transactions.
     */
    @NativeTransactional(propagation = Propagation.NESTED)
    Provider createProviderWithPropagationNested(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    // ========================================
    // Timeout Configuration
    // ========================================

    @NativeTransactional(timeout = 5)
    Provider createProviderWithTimeout5Seconds(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    @NativeTransactional(timeout = 1)
    Provider createProviderWithTimeout1Second(Map data) {
        // Simulate slow operation that exceeds timeout
        Thread.sleep(2000)
        return new Provider(data).save(failOnError: true)
    }

    @NativeTransactional(timeout = 10)
    Provider createProviderWithTimeout10Seconds(Map data) {
        Thread.sleep(500)
        return new Provider(data).save(failOnError: true)
    }

    // ========================================
    // RollbackFor - Single Exception
    // ========================================

    /**
     * Rolls back on IOException (a checked exception).
     * By default, only runtime exceptions trigger rollback.
     */
    @NativeTransactional(rollbackFor = [IOException.class])
    Provider createProviderWithRollbackForIOException(Map data, boolean shouldFail) throws IOException {
        def provider = new Provider(data).save(failOnError: true)
        if (shouldFail) {
            throw new IOException("Simulated IO exception")
        }
        return provider
    }

    @NativeTransactional(rollbackFor = [Exception.class])
    Provider createProviderWithRollbackForAllExceptions(Map data, boolean shouldFail) {
        def provider = new Provider(data).save(failOnError: true)
        if (shouldFail) {
            throw new Exception("Any exception")
        }
        return provider
    }

    // ========================================
    // RollbackFor - Multiple Exceptions
    // ========================================

    @NativeTransactional(rollbackFor = [IllegalArgumentException.class, IllegalStateException.class])
    Provider createProviderWithRollbackForMultipleExceptions(Map data, String exceptionType) {
        def provider = new Provider(data).save(failOnError: true)
        if (exceptionType == "IllegalArgument") {
            throw new IllegalArgumentException("Simulated argument exception")
        } else if (exceptionType == "IllegalState") {
            throw new IllegalStateException("Simulated state exception")
        }
        return provider
    }

    // ========================================
    // RollbackForClassName - Class Name Based
    // ========================================

    @NativeTransactional(rollbackForClassName = ["java.io.IOException"])
    Provider createProviderWithRollbackForClassNameIOException(Map data, boolean shouldFail) throws IOException {
        def provider = new Provider(data).save(failOnError: true)
        if (shouldFail) {
            throw new IOException("Simulated IO exception via className")
        }
        return provider
    }

    @NativeTransactional(rollbackForClassName = ["java.sql.SQLException", "java.net.SocketException"])
    Provider createProviderWithRollbackForMultipleClassNames(Map data, String exceptionType) throws java.sql.SQLException, java.net.SocketException {
        def provider = new Provider(data).save(failOnError: true)
        if (exceptionType == "SQL") {
            throw new java.sql.SQLException("Simulated SQL exception")
        } else if (exceptionType == "Socket") {
            throw new java.net.SocketException("Simulated socket exception")
        }
        return provider
    }

    // ========================================
    // NoRollbackFor - Single Exception
    // ========================================

    /**
     * Does NOT roll back on BusinessException.
     * Useful for business logic exceptions that should commit partial work.
     */
    @NativeTransactional(noRollbackFor = [BusinessException.class])
    Provider createProviderWithNoRollbackForBusinessException(Map data, boolean shouldThrow) {
        def provider = new Provider(data).save(failOnError: true)
        if (shouldThrow) {
            throw new BusinessException("Business rule violation - should not rollback")
        }
        return provider
    }

    // ========================================
    // NoRollbackFor - Multiple Exceptions
    // ========================================

    @NativeTransactional(noRollbackFor = [CustomValidationException.class, BusinessException.class])
    Provider createProviderWithNoRollbackForMultipleExceptions(Map data, String exceptionType) {
        def provider = new Provider(data).save(failOnError: true)
        if (exceptionType == "Business") {
            throw new BusinessException("Business exception")
        } else if (exceptionType == "Validation") {
            throw new CustomValidationException("Validation exception")
        }
        return provider
    }

    // ========================================
    // NoRollbackForClassName - Class Name Based
    // ========================================

    @NativeTransactional(noRollbackForClassName = ["example.BusinessException"])
    Provider createProviderWithNoRollbackForClassName(Map data, boolean shouldThrow) {
        def provider = new Provider(data).save(failOnError: true)
        if (shouldThrow) {
            throw new BusinessException("Business exception via className")
        }
        return provider
    }

    @NativeTransactional(noRollbackForClassName = ["example.BusinessException", "example.CustomValidationException"])
    Provider createProviderWithNoRollbackForMultipleClassNames(Map data, String exceptionType) {
        def provider = new Provider(data).save(failOnError: true)
        if (exceptionType == "Business") {
            throw new BusinessException("Business exception")
        } else if (exceptionType == "Validation") {
            throw new CustomValidationException("Validation exception")
        }
        return provider
    }

    // ========================================
    // Combined Parameters
    // ========================================

    /**
     * Demonstrates combining multiple transaction attributes.
     */
    @NativeTransactional(
        propagation = Propagation.REQUIRES_NEW,
        timeout = 5,
        rollbackFor = [IOException.class],
        noRollbackFor = [BusinessException.class]
    )
    Provider createProviderWithCombinedParameters(Map data, String scenario) throws IOException {
        def provider = new Provider(data).save(failOnError: true)

        switch (scenario) {
            case "timeout":
                Thread.sleep(6000) // Exceeds timeout
                break
            case "rollbackException":
                throw new IOException("Should rollback")
            case "noRollbackException":
                throw new BusinessException("Should NOT rollback")
            case "success":
                // Do nothing, succeed
                break
        }

        return provider
    }

    /**
     * Tests precedence when an exception matches both rollbackFor and noRollbackFor.
     * NoRollbackFor takes precedence.
     */
    @NativeTransactional(
        propagation = Propagation.REQUIRED,
        rollbackFor = [Exception.class],
        noRollbackFor = [BusinessException.class, CustomValidationException.class]
    )
    Provider createProviderWithConflictingRules(Map data, String exceptionType) {
        def provider = new Provider(data).save(failOnError: true)

        if (exceptionType == "Runtime") {
            throw new RuntimeException("Should rollback")
        } else if (exceptionType == "Business") {
            throw new BusinessException("Should NOT rollback - noRollbackFor takes precedence")
        }

        return provider
    }

    // ========================================
    // Mixed Propagation Scenarios
    // ========================================

    /**
     * Creates provider with audit in independent transaction.
     * Demonstrates mixing propagation levels within a single operation.
     */
    Map createProviderWithIndependentAuditRequiresNew(Map providerData) {
        // Uses service-level @NativeTransactional (REQUIRED)
        def provider = new Provider(providerData).save(failOnError: true)

        // Audit created in NEW independent transaction (REQUIRES_NEW)
        def audit = createAuditEventWithPropagationRequiresNew(provider.id.toString(), "CREATE")

        return [provider: provider, audit: audit]
    }

    /**
     * Creates provider with audit that rolls back independently.
     * Even if outer transaction fails, audit is already committed.
     */
    Map createProviderWithAuditThenFailDemonstratingRollback(Map providerData) {
        def provider = new Provider(providerData).save(failOnError: true)

        // Audit committed in independent transaction
        createAuditEventWithPropagationRequiresNew(provider.id.toString(), "CREATE_ATTEMPT")

        // This failure rolls back provider, but NOT audit
        throw new RuntimeException("Simulated failure")
    }

    // ========================================
    // Mixed Declarative and Programmatic Transactions
    // ========================================

    /**
     * Combines @NativeTransactional (declarative) with withNativeTransaction (programmatic).
     */
    @NativeTransactional(propagation = Propagation.REQUIRED)
    Map createProviderWithMixedDeclarativeAndProgrammaticTransactions(Map data) {
        def provider = new Provider(data).save(failOnError: true)

        // Nested programmatic transaction
        def audit = AuditEvent.withNativeTransaction {
            new AuditEvent(
                entityId: provider.id.toString(),
                entityType: "Provider",
                action: "CREATED_WITH_PROGRAMMATIC",
                performedBy: "SYSTEM",
                timestamp: new Date()
            ).save(failOnError: true)
        }

        return [provider: provider, audit: audit]
    }

    // ========================================
    // Default Rollback Behavior Tests (No explicit rollbackFor)
    // ========================================

    /**
     * Tests default Spring behavior: should rollback on RuntimeException
     * even when NO rollbackFor is specified.
     *
     * This is the most common use case and should work like standard @Transactional.
     */
    @NativeTransactional
    Provider createProviderWithDefaultRollbackOnRuntimeException(Map data) {
        def provider = new Provider(data).save(failOnError: true)
        throw new RuntimeException("Should rollback by default - Spring's standard behavior")
    }

    /**
     * Tests default behavior with successful completion (no exception).
     * Should commit normally.
     */
    @NativeTransactional
    Provider createProviderWithDefaultBehaviorSuccess(Map data) {
        return new Provider(data).save(failOnError: true)
    }

    /**
     * Tests that checked exceptions (IOException) do NOT rollback by default,
     * matching Spring's standard @Transactional behavior.
     */
    @NativeTransactional
    Provider createProviderWithDefaultBehaviorCheckedException(Map data, boolean shouldFail) throws IOException {
        def provider = new Provider(data).save(failOnError: true)
        if (shouldFail) {
            throw new IOException("Checked exception - should NOT rollback by default")
        }
        return provider
    }

    /**
     * Tests default behavior with Error (should rollback like RuntimeException).
     */
    @NativeTransactional
    Provider createProviderWithDefaultRollbackOnError(Map data) {
        def provider = new Provider(data).save(failOnError: true)
        throw new AssertionError("Error should rollback by default")
    }

    /**
     * Tests default behavior with explicit REQUIRED propagation.
     */
    @NativeTransactional(propagation = Propagation.REQUIRED)
    Provider createProviderWithRequiredAndDefaultRollback(Map data) {
        def provider = new Provider(data).save(failOnError: true)
        throw new RuntimeException("REQUIRED with default rollback")
    }

    /**
     * Tests default behavior with REQUIRES_NEW propagation.
     */
    @NativeTransactional(propagation = Propagation.REQUIRES_NEW)
    Provider createProviderWithRequiresNewAndDefaultRollback(Map data) {
        def provider = new Provider(data).save(failOnError: true)
        throw new RuntimeException("REQUIRES_NEW with default rollback")
    }
}

// ========================================
// Custom Exception Classes for Testing
// ========================================

/**
 * Business exception - represents a business rule violation.
 * Typically used with noRollbackFor to commit partial work.
 */
class BusinessException extends RuntimeException {
    BusinessException(String message) {
        super(message)
    }
}

/**
 * Custom validation exception for testing.
 */
class CustomValidationException extends RuntimeException {
    CustomValidationException(String message) {
        super(message)
    }
}
