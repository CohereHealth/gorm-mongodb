package org.grails.datastore.mapping.mongo.config

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.mongo.NativeTransactionalAttributeSource
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Role
import org.springframework.core.Ordered
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor
import org.springframework.transaction.interceptor.TransactionInterceptor

/**
 * Spring configuration that registers the custom NativeTransactionalAttributeSource
 * and wires it into Spring AOP transaction infrastructure.
 */
@CompileStatic
@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
class MongoNativeTransactionAopConfiguration {

    /**
     * Creates the custom TransactionAttributeSource that detects @NativeTransactional annotations
     * and returns NativeTransactionAttribute instances with labels for identification.
     */
    @Bean(name = "nativeTransactionalAttributeSource")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    NativeTransactionalAttributeSource nativeTransactionalAttributeSource() {
        return new NativeTransactionalAttributeSource()
    }

    /**
     * Creates the TransactionInterceptor that will intercept method calls to
     * @NativeTransactional methods and delegate to the transaction manager.
     *
     * IMPORTANT: The interceptor is configured to use the primary transaction manager
     * directly. The label in NativeTransactionAttribute is used ONLY by
     * MongoDatastoreTransactionManager.isNativeTransactionalDefinition() to detect
     * @NativeTransactional annotations, not for bean resolution.
     */
    @Bean(name = "nativeTransactionInterceptor")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    TransactionInterceptor nativeTransactionInterceptor(
            PlatformTransactionManager transactionManager,
            NativeTransactionalAttributeSource attributeSource) {

        TransactionInterceptor interceptor = new TransactionInterceptor()
        interceptor.setTransactionManager(transactionManager)
        interceptor.setTransactionAttributeSource(attributeSource)
        // Set transactionManagerBeanName to empty string to prevent qualifier-based lookup
        interceptor.setTransactionManagerBeanName("")

        return interceptor
    }

    /**
     * Creates the advisor that integrates the transaction interceptor into Spring AOP.
     * This advisor will cause Spring to create proxies around beans with @NativeTransactional methods.
     */
    @Bean(name = "nativeTransactionAdvisor")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    BeanFactoryTransactionAttributeSourceAdvisor nativeTransactionAdvisor(
            TransactionInterceptor nativeTransactionInterceptor,
            NativeTransactionalAttributeSource attributeSource) {

        BeanFactoryTransactionAttributeSourceAdvisor advisor =
            new BeanFactoryTransactionAttributeSourceAdvisor()
        advisor.setAdvice(nativeTransactionInterceptor)
        advisor.setTransactionAttributeSource(attributeSource)

        // Lower priority than default @Transactional advisor (LOWEST_PRECEDENCE).
        // Avoids interfering with standard Spring transaction proxies.
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 1)

        return advisor
    }
}
