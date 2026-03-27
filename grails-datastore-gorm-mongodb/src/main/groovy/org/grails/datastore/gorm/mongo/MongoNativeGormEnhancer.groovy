package org.grails.datastore.gorm.mongo

import groovy.transform.CompileStatic
import org.grails.datastore.gorm.GormEnhancer
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.connections.MongoConnectionSourceSettings
import org.springframework.transaction.PlatformTransactionManager

@CompileStatic
class MongoNativeGormEnhancer extends MongoGormEnhancer {

    MongoNativeGormEnhancer(MongoDatastore datastore, PlatformTransactionManager transactionManager, MongoConnectionSourceSettings settings) {
        super(datastore, transactionManager, settings)
    }

    MongoNativeGormEnhancer(MongoDatastore datastore, PlatformTransactionManager transactionManager, boolean failOnError = false) {
        super(datastore, transactionManager, failOnError)
    }
}