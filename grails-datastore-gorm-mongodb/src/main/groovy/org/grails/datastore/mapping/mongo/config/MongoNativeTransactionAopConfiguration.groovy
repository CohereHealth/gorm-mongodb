package org.grails.datastore.mapping.mongo.config

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.mongo.MongoDatastoreTransactionManager
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
 *
 * <p>This enables automatic detection and handling of @NativeTransactional annotations
 * through Spring's proxy-based AOP mechanism.</p>
 *
 * <p>The configuration creates three beans:</p>
 * <ul>
 *   <li>NativeTransactionalAttributeSource - Detects @NativeTransactional annotations</li>
 *   <li>TransactionInterceptor - Intercepts method calls and manages transactions</li>
 *   <li>BeanFactoryTransactionAttributeSourceAdvisor - Wires the interceptor into Spring AOP</li>
 * </ul>
 */
@CompileStatic
@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
class MongoNativeTransactionAopConfiguration {

    /**
     * Creates the custom TransactionAttributeSource that detects @NativeTransactional annotations
     * and marks them with the "nativeTransaction" qualifier.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    NativeTransactionalAttributeSource nativeTransactionalAttributeSource() {
        return new NativeTransactionalAttributeSource()
    }

    /**
     * Creates the TransactionInterceptor that will intercept method calls to
     * @NativeTransactional methods and delegate to the transaction manager.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    TransactionInterceptor nativeTransactionInterceptor(
            PlatformTransactionManager transactionManager,
            NativeTransactionalAttributeSource attributeSource) {

        TransactionInterceptor interceptor = new TransactionInterceptor()
        interceptor.setTransactionManager(transactionManager)
        interceptor.setTransactionAttributeSource(attributeSource)

        return interceptor
    }

    /**
     * Creates the advisor that integrates the transaction interceptor into Spring AOP.
     * This advisor will cause Spring to create proxies around beans with @NativeTransactional methods.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    BeanFactoryTransactionAttributeSourceAdvisor nativeTransactionAdvisor(
            TransactionInterceptor nativeTransactionInterceptor,
            NativeTransactionalAttributeSource attributeSource) {

        BeanFactoryTransactionAttributeSourceAdvisor advisor =
            new BeanFactoryTransactionAttributeSourceAdvisor()
        advisor.setAdvice(nativeTransactionInterceptor)
        advisor.setTransactionAttributeSource(attributeSource)

        // Higher priority than default @Transactional advisor
        // This ensures @NativeTransactional is detected before @Transactional
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 1)

        return advisor
    }
}
