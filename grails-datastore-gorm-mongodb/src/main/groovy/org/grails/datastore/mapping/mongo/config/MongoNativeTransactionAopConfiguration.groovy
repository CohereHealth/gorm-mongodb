package org.grails.datastore.mapping.mongo.config

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.mongo.MongoDatastoreTransactionManager
import org.grails.datastore.mapping.mongo.NativeTransactionalAttributeSource
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator
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
 *   <li>InfrastructureAdvisorAutoProxyCreator - Creates proxies for infrastructure advisors</li>
 * </ul>
 */
@CompileStatic
@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
class MongoNativeTransactionAopConfiguration {

    /**
     * Registers the InfrastructureAdvisorAutoProxyCreator to enable automatic proxy creation
     * for infrastructure advisors (like our transaction advisor).
     *
     * This is necessary because without @EnableTransactionManagement, Spring won't automatically
     * create proxies for @NativeTransactional methods.
     */
    @Bean(name = "org.springframework.aop.config.internalAutoProxyCreator")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static InfrastructureAdvisorAutoProxyCreator infrastructureAdvisorAutoProxyCreator() {
        return new InfrastructureAdvisorAutoProxyCreator()
    }

    /**
     * Creates the custom TransactionAttributeSource that detects @NativeTransactional annotations
     * and marks them with the "nativeTransaction" qualifier.
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
     * directly. The "nativeTransaction" qualifier in TransactionAttribute is used ONLY
     * by MongoDatastoreTransactionManager.isNativeTransactionalDefinition() to detect
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

        // Highest priority to ensure @NativeTransactional is detected before any other
        // transaction advisors. In Spring, LOWER values = HIGHER priority.
        // Use Ordered.HIGHEST_PRECEDENCE to ensure this runs first.
        advisor.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)

        return advisor
    }
}
