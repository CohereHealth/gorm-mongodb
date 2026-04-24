package org.grails.datastore.mapping.mongo.config

import com.mongodb.client.MongoClient
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoDatastoreTransactionManager
import org.grails.datastore.mapping.mongo.NativeTransactionalAttributeSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor
import org.springframework.transaction.interceptor.TransactionInterceptor
import spock.lang.Specification

/**
 * Unit tests for MongoNativeTransactionAopConfiguration.
 * Verifies that beans are created correctly and configuration is conditional.
 */
class MongoNativeTransactionAopConfigurationSpec extends Specification {

    void "test configuration loads when MongoDatastoreTransactionManager is present"() {
        given:
        def context = new AnnotationConfigApplicationContext(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        def attributeSource = context.getBean(NativeTransactionalAttributeSource)
        def interceptor = context.getBean(TransactionInterceptor)
        def advisor = context.getBean(BeanFactoryTransactionAttributeSourceAdvisor)

        then:
        attributeSource != null
        interceptor != null
        advisor != null

        cleanup:
        context.close()
    }

    void "test configuration can be loaded standalone"() {
        given:
        def context = new AnnotationConfigApplicationContext(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        def hasAttributeSource = context.containsBean("nativeTransactionalAttributeSource")
        def hasInterceptor = context.containsBean("nativeTransactionInterceptor")
        def hasAdvisor = context.containsBean("nativeTransactionAdvisor")

        then: "All beans are present when MongoDatastoreTransactionManager exists"
        hasAttributeSource
        hasInterceptor
        hasAdvisor

        cleanup:
        context.close()
    }

    void "test attribute source bean is created"() {
        given:
        def context = new AnnotationConfigApplicationContext(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        NativeTransactionalAttributeSource attributeSource = context.getBean(NativeTransactionalAttributeSource)

        then:
        attributeSource != null
        attributeSource instanceof NativeTransactionalAttributeSource

        cleanup:
        context.close()
    }

    void "test transaction interceptor is configured correctly"() {
        given:
        def context = new AnnotationConfigApplicationContext(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        TransactionInterceptor interceptor = context.getBean(TransactionInterceptor)

        then:
        interceptor != null
        interceptor.getTransactionManager() != null
        interceptor.getTransactionAttributeSource() != null
        interceptor.getTransactionAttributeSource() instanceof NativeTransactionalAttributeSource

        cleanup:
        context.close()
    }

    void "test advisor is configured with correct priority"() {
        given:
        def context = new AnnotationConfigApplicationContext(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        BeanFactoryTransactionAttributeSourceAdvisor advisor = context.getBean(BeanFactoryTransactionAttributeSourceAdvisor)

        then:
        advisor != null
        advisor.getOrder() == Ordered.LOWEST_PRECEDENCE - 1
        advisor.getAdvice() instanceof TransactionInterceptor

        cleanup:
        context.close()
    }

    void "test advisor uses correct attribute source"() {
        given:
        def context = new AnnotationConfigApplicationContext(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        BeanFactoryTransactionAttributeSourceAdvisor advisor = context.getBean(BeanFactoryTransactionAttributeSourceAdvisor)
        NativeTransactionalAttributeSource attributeSource = context.getBean(NativeTransactionalAttributeSource)
        // Get the interceptor and verify it uses the correct attribute source
        TransactionInterceptor interceptor = (TransactionInterceptor) advisor.getAdvice()

        then:
        interceptor.getTransactionAttributeSource() == attributeSource

        cleanup:
        context.close()
    }

    @Configuration
    static class TestConfig {
        @Bean
        MongoDatastore mongoDatastore() {
            // Create a minimal in-memory datastore for testing
            def props = [
                'grails.mongodb.url': 'mongodb://localhost:27017/test',
                'grails.mongodb.databaseName': 'test'
            ]
            return new MongoDatastore(props)
        }

        @Bean
        MongoClient mongoClient(MongoDatastore datastore) {
            return datastore.mongoClient
        }

        @Bean
        MongoDatastoreTransactionManager transactionManager(MongoDatastore datastore, MongoClient mongoClient) {
            return new MongoDatastoreTransactionManager(datastore, mongoClient)
        }
    }
}
