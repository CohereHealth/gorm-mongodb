package org.grails.datastore.mapping.mongo

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Inherited
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Marker annotation for integration test classes that use {@code @Rollback}
 * with MongoDB native transactions.
 *
 * <p>When applied alongside {@code @Rollback}, this annotation causes the
 * transaction manager to use a real MongoDB {@code ClientSession} with
 * {@code abortTransaction()} for rollback, instead of Spring's in-memory
 * rollback which doesn't work with native MongoDB operations.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * @Integration
 * @Rollback
 * @NativeRollback
 * class MyIntegrationSpec extends Specification {
 *     // All operations use the same ClientSession
 *     // Transaction is aborted after each test
 * }
 * }</pre>
 *
 * @author Puneet Behl
 * @since 6.x
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@interface NativeRollback {
}
