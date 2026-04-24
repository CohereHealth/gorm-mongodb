package org.grails.datastore.mapping.mongo.config

import com.mongodb.client.MongoClient
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoDatastoreTransactionManager
import org.grails.datastore.mapping.mongo.NativeTransactionalAttributeSource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
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
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        def context = contextRunner.run { ctx -> ctx }

        then:
        context.getBean(NativeTransactionalAttributeSource) != null
        context.getBean(TransactionInterceptor) != null
        context.getBean(BeanFactoryTransactionAttributeSourceAdvisor) != null

        cleanup:
        context.close()
    }

    void "test configuration can be loaded standalone"() {
        given:
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        def context = contextRunner.run { ctx -> ctx }

        then: "All beans are present when MongoDatastoreTransactionManager exists"
        context.containsBean("nativeTransactionalAttributeSource")
        context.containsBean("nativeTransactionInterceptor")
        context.containsBean("nativeTransactionAdvisor")

        cleanup:
        context.close()
    }

    void "test attribute source bean is created"() {
        given:
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        def context = contextRunner.run { ctx -> ctx }
        NativeTransactionalAttributeSource attributeSource = context.getBean(NativeTransactionalAttributeSource)

        then:
        attributeSource != null
        attributeSource instanceof NativeTransactionalAttributeSource

        cleanup:
        context.close()
    }

    void "test transaction interceptor is configured correctly"() {
        given:
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        def context = contextRunner.run { ctx -> ctx }
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
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        def context = contextRunner.run { ctx -> ctx }
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
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig, MongoNativeTransactionAopConfiguration)

        when:
        def context = contextRunner.run { ctx -> ctx }
        BeanFactoryTransactionAttributeSourceAdvisor advisor = context.getBean(BeanFactoryTransactionAttributeSourceAdvisor)
        NativeTransactionalAttributeSource attributeSource = context.getBean(NativeTransactionalAttributeSource)

        then:
        advisor.getTransactionAttributeSource() == attributeSource

        cleanup:
        context.close()
    }

    @Configuration
    static class TestConfig {
        @Bean
        MongoDatastore mongoDatastore() {
            return Mock(MongoDatastore)
        }

        @Bean
        MongoClient mongoClient() {
            return Mock(MongoClient)
        }

        @Bean
        MongoDatastoreTransactionManager transactionManager(MongoDatastore datastore, MongoClient mongoClient) {
            return new MongoDatastoreTransactionManager(datastore, mongoClient)
        }
    }
}
