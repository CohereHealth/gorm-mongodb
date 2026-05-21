package org.grails.datastore.mapping.mongo;

import org.springframework.transaction.annotation.Propagation;
import java.lang.annotation.*;

/**
 * Declarative annotation for MongoDB native transactions.
 *
 * <p>Similar to Spring's @Transactional, but specifically
 * uses MongoDB ClientSession with multi-document ACID transactions.</p>
 *
 * <p>This annotation works through MongoNativeTransactionAopConfiguration which registers
 * a custom TransactionAttributeSource (NativeTransactionalAttributeSource) with highest
 * priority. This attribute source intercepts @NativeTransactional annotations and returns
 * NativeTransactionAttribute instances to enable native MongoDB transaction handling.</p>
 *
 * <p>Unlike Spring's @Transactional, this annotation does NOT use @Transactional as a
 * meta-annotation, which allows it to work without @EnableTransactionManagement and
 * prevents conflicts with GORM event listeners that could cause infinite recursion.</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface NativeTransactional {

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
