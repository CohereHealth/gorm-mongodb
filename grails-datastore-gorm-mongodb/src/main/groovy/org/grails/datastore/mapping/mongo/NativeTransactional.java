package org.grails.datastore.mapping.mongo;

import org.springframework.transaction.annotation.Propagation;
import java.lang.annotation.*;

/**
 * Declarative annotation for MongoDB native transactions.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface NativeTransactional {

    /**
     * Transaction propagation behavior.
     * <p>Default is {@link Propagation#REQUIRED}.</p>
     * <p><strong>Supported values:</strong> Only {@link Propagation#REQUIRED} and
     * {@link Propagation#REQUIRES_NEW} are currently supported for native MongoDB transactions.
     * Attempting to use other propagation levels will throw {@code UnsupportedOperationException}
     * at transaction begin time. For other propagation levels, use standard {@code @Transactional}
     * instead.</p>
     */
    Propagation propagation() default Propagation.REQUIRED;

    /**
     * Timeout for the transaction in seconds.
     * <p><strong>Note:</strong> The timeout attribute is currently not enforced on native
     * MongoDB transactions. Spring's transaction infrastructure tracks the timeout, but it
     * is not applied to the MongoDB ClientSession. Native transactions may run longer than
     * the specified timeout without automatic rollback. For timeout enforcement, consider
     * using standard {@code @Transactional} instead.</p>
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
