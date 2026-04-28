package org.grails.datastore.mapping.mongo;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.lang.annotation.*;

/**
 * Declarative annotation for MongoDB native transactions.
 *
 * <p>Similar to Spring's {@link Transactional @Transactional}, but specifically
 * uses MongoDB ClientSession with multi-document ACID transactions.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Transactional  // Meta-annotation - makes this recognized by Spring
public @interface NativeTransactional {

    /**
     * Qualifier constant used to identify native MongoDB transactions.
     * This qualifier is used internally by the transaction manager to distinguish
     * native transactions from regular Spring transactions.
     */
    String NATIVE_TRANSACTION_QUALIFIER = "nativeTransaction";

    /**
     * Transaction propagation behavior.
     * <p>Default is {@link Propagation#REQUIRED}.</p>
     */
    Propagation propagation() default Propagation.REQUIRED;

    /**
     * Timeout for the transaction in seconds.
     * <p>This timeout is enforced by Spring's transaction infrastructure. If the transaction
     * takes longer than the specified timeout, Spring will attempt to roll it back.
     */
    int timeout() default -1;

    /**
     * Exception types that should cause rollback.
     * <p>By default, only runtime exceptions and errors cause rollback.
     * Use this to specify checked exceptions that should also trigger rollback.</p>
     */
    Class<? extends Throwable>[] rollbackFor() default {};

    /**
     * Exception class names that should cause rollback.
     * <p>Alternative to {@link #rollbackFor()} for specifying exceptions by name.</p>
     */
    String[] rollbackForClassName() default {};

    /**
     * Exception types that should NOT cause rollback.
     * <p>Useful for business exceptions that should not rollback the entire transaction.</p>
     */
    Class<? extends Throwable>[] noRollbackFor() default {};

    /**
     * Exception class names that should NOT cause rollback.
     * <p>Alternative to {@link #noRollbackFor()} for specifying exceptions by name.</p>
     */
    String[] noRollbackForClassName() default {};
}
