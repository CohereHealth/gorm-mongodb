package example

import org.spockframework.runtime.extension.ExtensionAnnotation

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Annotation to wrap test methods in MongoDB native transactions.
 * Allows verification of data within transaction before automatic rollback.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@ExtensionAnnotation(MongoNativeTransactionExtension.class)
@interface MongoNativeTransaction {
}