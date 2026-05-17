package org.grails.datastore.mapping.mongo

import groovy.transform.CompileStatic
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute

/**
 * Custom TransactionAttribute subclass that marks a transaction as a native MongoDB transaction.
 *
 * <p>This class serves as a marker to identify @NativeTransactional annotations without using
 * a qualifier, which would interfere with Spring's transaction manager bean resolution.</p>
 *
 * <p>The MongoDatastoreTransactionManager checks for this type to determine whether to use
 * native ClientSession transactions or legacy flush-based transactions.</p>
 */
@CompileStatic
class NativeTransactionAttribute extends RuleBasedTransactionAttribute {
    private static final long serialVersionUID = 1L

    /**
     * Marker method to identify this as a native transaction attribute.
     * @return true always
     */
    boolean isNativeTransaction() {
        return true
    }
}
