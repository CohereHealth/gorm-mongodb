package org.grails.datastore.mapping.mongo.config

import groovy.transform.CompileStatic

@CompileStatic
class MongoNativeTransactionConfiguration {
    
    /**
     * Configuration key for enabling native transactions globally
     */
    public static final String NATIVE_TRANSACTIONS_ENABLED = "grails.mongodb.nativeTransactions"
    
    /**
     * Configuration key for test environment detection
     */
    public static final String GRAILS_ENV = "grails.env"
    
    /**
     * Test environment value
     */
    public static final String TEST_ENV = "test"
}