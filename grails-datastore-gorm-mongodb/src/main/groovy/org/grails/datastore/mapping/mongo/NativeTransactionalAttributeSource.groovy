package org.grails.datastore.mapping.mongo

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute
import org.springframework.transaction.interceptor.RollbackRuleAttribute
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute
import org.springframework.transaction.interceptor.TransactionAttribute
import org.springframework.transaction.interceptor.TransactionAttributeSource

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom TransactionAttributeSource that detects @NativeTransactional annotations
 * and creates TransactionAttribute instances marked with the "nativeTransaction" qualifier.
 */
@Slf4j
@CompileStatic
class NativeTransactionalAttributeSource implements TransactionAttributeSource, Serializable {

    private static final long serialVersionUID = 1L

    private final Map<Object, TransactionAttribute> attributeCache = new ConcurrentHashMap<>(1024)
    private static final TransactionAttribute NULL_TRANSACTION_ATTRIBUTE = new NullTransactionAttribute()

    @Override
    TransactionAttribute getTransactionAttribute(Method method, Class<?> targetClass) {
        if (method.getDeclaringClass() == Object.class) {
            return null
        }
        Object cacheKey = getCacheKey(method, targetClass)
        TransactionAttribute cached = attributeCache.get(cacheKey)
        if (cached != null) {
            return cached == NULL_TRANSACTION_ATTRIBUTE ? null : cached
        }
        NativeTransactional annotation = findAnnotation(method, targetClass)
        if (annotation == null) {
            attributeCache.put(cacheKey, NULL_TRANSACTION_ATTRIBUTE)
            return null
        }
        TransactionAttribute txAttr = buildTransactionAttribute(annotation)
        attributeCache.put(cacheKey, txAttr)
        if (log.isDebugEnabled()) {
            log.debug("Found @NativeTransactional on {}.{}", targetClass?.simpleName, method.name)
        }
        return txAttr
    }

    /**
     * Finds @NativeTransactional annotation on method or class.
     * Method-level annotation takes precedence over class-level.
     */
    private NativeTransactional findAnnotation(Method method, Class<?> targetClass) {
        // Check method first
        NativeTransactional annotation = AnnotatedElementUtils.findMergedAnnotation(method, NativeTransactional)
        if (annotation != null) {
            return annotation
        }
        // Check class (if targetClass is provided)
        if (targetClass != null) {
            return AnnotatedElementUtils.findMergedAnnotation(targetClass, NativeTransactional)
        }
        return null
    }

    /**
     * Builds TransactionAttribute from @NativeTransactional annotation.
     * Returns a NativeTransactionAttribute instance as a marker for native transactions,
     * avoiding qualifier-based bean lookup which would interfere with Spring's
     * transaction manager resolution.
     */
    private TransactionAttribute buildTransactionAttribute(NativeTransactional annotation) {
        NativeTransactionAttribute txAttr = new NativeTransactionAttribute()
        txAttr.setPropagationBehavior(annotation.propagation().value())
        txAttr.setTimeout(annotation.timeout())
        // Set a label to identify native transactions even when Spring's TransactionInterceptor
        // wraps our attribute in a DelegatingTransactionAttribute.
        // DelegatingTransactionAttribute delegates getLabels() to the wrapped attribute.
        // Labels are NOT used for bean resolution (unlike qualifier), making them safe as markers.
        txAttr.setLabels(Collections.singleton(NativeTransactionAttribute.NATIVE_TX_NAME_PREFIX))
        List<RollbackRuleAttribute> rollbackRules = new ArrayList<>()
        for (Class<?> rbRule : annotation.rollbackFor()) {
            rollbackRules.add(new RollbackRuleAttribute(rbRule))
        }
        for (String rbRuleName : annotation.rollbackForClassName()) {
            rollbackRules.add(new RollbackRuleAttribute(rbRuleName))
        }
        for (Class<?> nrbRule : annotation.noRollbackFor()) {
            rollbackRules.add(new NoRollbackRuleAttribute(nrbRule))
        }
        for (String nrbRuleName : annotation.noRollbackForClassName()) {
            rollbackRules.add(new NoRollbackRuleAttribute(nrbRuleName))
        }
        txAttr.setRollbackRules(rollbackRules)
        // Note: Do NOT set qualifier - it would cause Spring to look for a bean with that name
        return txAttr
    }

    /**
     * Creates cache key from method and target class.
     */
    private Object getCacheKey(Method method, Class<?> targetClass) {
        return new MethodClassKey(method, targetClass)
    }

    /**
     * Cache key for method + target class combination.
     */
    @CompileStatic
    private static class MethodClassKey implements Serializable {
        private static final long serialVersionUID = 1L

        private final Method method
        private final Class<?> targetClass

        MethodClassKey(Method method, Class<?> targetClass) {
            this.method = method
            this.targetClass = targetClass
        }

        @Override
        boolean equals(Object other) {
            if (this.is(other)) {
                return true
            }
            if (!(other instanceof MethodClassKey)) {
                return false
            }
            MethodClassKey otherKey = (MethodClassKey) other
            return this.method.equals(otherKey.method) &&
                   this.targetClass == otherKey.targetClass
        }

        @Override
        int hashCode() {
            return method.hashCode() * 31 + (targetClass != null ? targetClass.hashCode() : 0)
        }
    }

    /**
     * Marker for null cache entries (to distinguish "not found" from "not cached yet").
     */
    @CompileStatic
    private static class NullTransactionAttribute extends RuleBasedTransactionAttribute {
        private static final long serialVersionUID = 1L
    }
}
