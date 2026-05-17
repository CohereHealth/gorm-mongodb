package org.grails.datastore.mapping.mongo

import groovy.transform.CompileStatic
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute

/**
 * Custom TransactionAttribute subclass that marks a transaction as a native MongoDB transaction.
 */
@CompileStatic
class NativeTransactionAttribute extends RuleBasedTransactionAttribute {
    private static final long serialVersionUID = 1L

    /**
     * Unique prefix used as transaction name to identify native transactions.
     * Spring's TransactionInterceptor wraps our attribute in a DelegatingTransactionAttribute,
     * so instanceof checks fail. The name survives the wrapping and can be checked in doBegin.
     */
    public static final String NATIVE_TX_NAME_PREFIX = "NATIVE_MONGO_TX::"

    /**
     * Marker method to identify this as a native transaction attribute.
     * @return true always
     */
    boolean isNativeTransaction() {
        return true
    }
}
