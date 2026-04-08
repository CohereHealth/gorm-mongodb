package example

import org.spockframework.runtime.extension.ExtensionAnnotation

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Annotation to wrap test methods in MongoDB native transactions that are
 * automatically rolled back after each test, similar to {@code @Rollback}.
 *
 * Can be applied at class level (all tests) or method level (individual tests).
 */
@Target([ElementType.TYPE, ElementType.METHOD])
@Retention(RetentionPolicy.RUNTIME)
@ExtensionAnnotation(MongoNativeTransactionExtension.class)
@interface MongoNativeTransaction {
}
