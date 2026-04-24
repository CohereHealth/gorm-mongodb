package org.grails.datastore.mapping.mongo

import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.interceptor.TransactionAttribute
import spock.lang.Specification

import java.lang.reflect.Method

/**
 * Unit tests for NativeTransactionalAttributeSource.
 * Verifies annotation detection, attribute extraction, and caching behavior.
 */
class NativeTransactionalAttributeSourceSpec extends Specification {

    NativeTransactionalAttributeSource attributeSource

    void setup() {
        attributeSource = new NativeTransactionalAttributeSource()
    }

    void "test detects method-level @NativeTransactional annotation"() {
        given:
        Method method = TestService.getMethod("methodLevelTransactional")

        when:
        TransactionAttribute attr = attributeSource.getTransactionAttribute(method, TestService)

        then:
        attr != null
        attr.getQualifier() == "nativeTransaction"
        attr.getPropagationBehavior() == Propagation.REQUIRED.value()
    }

    void "test detects class-level @NativeTransactional annotation"() {
        given:
        Method method = ClassLevelTestService.getMethod("someMethod")

        when:
        TransactionAttribute attr = attributeSource.getTransactionAttribute(method, ClassLevelTestService)

        then:
        attr != null
        attr.getQualifier() == "nativeTransaction"
        attr.getPropagationBehavior() == Propagation.REQUIRED.value()
    }

    void "test method-level annotation overrides class-level"() {
        given:
        Method method = ClassLevelTestService.getMethod("methodLevelOverride")

        when:
        TransactionAttribute attr = attributeSource.getTransactionAttribute(method, ClassLevelTestService)

        then:
        attr != null
        attr.getQualifier() == "nativeTransaction"
        attr.getPropagationBehavior() == Propagation.REQUIRES_NEW.value()
    }

    void "test returns null for non-annotated methods"() {
        given:
        Method method = TestService.getMethod("notTransactional")

        when:
        TransactionAttribute attr = attributeSource.getTransactionAttribute(method, TestService)

        then:
        attr == null
    }

    void "test extracts propagation level"() {
        given:
        Method method = TestService.getMethod("requiresNewTransaction")

        when:
        TransactionAttribute attr = attributeSource.getTransactionAttribute(method, TestService)

        then:
        attr != null
        attr.getPropagationBehavior() == Propagation.REQUIRES_NEW.value()
    }

    void "test extracts timeout"() {
        given:
        Method method = TestService.getMethod("timeoutTransaction")

        when:
        TransactionAttribute attr = attributeSource.getTransactionAttribute(method, TestService)

        then:
        attr != null
        attr.getTimeout() == 30
    }

    void "test extracts rollbackFor rules"() {
        given:
        Method method = TestService.getMethod("rollbackForTransaction")

        when:
        TransactionAttribute attr = attributeSource.getTransactionAttribute(method, TestService)

        then:
        attr != null
        attr.rollbackOn(new IOException())
    }

    void "test extracts noRollbackFor rules"() {
        given:
        Method method = TestService.getMethod("noRollbackForTransaction")

        when:
        TransactionAttribute attr = attributeSource.getTransactionAttribute(method, TestService)

        then:
        attr != null
        !attr.rollbackOn(new IllegalArgumentException())
    }

    void "test caches transaction attributes"() {
        given:
        Method method = TestService.getMethod("methodLevelTransactional")

        when:
        TransactionAttribute attr1 = attributeSource.getTransactionAttribute(method, TestService)
        TransactionAttribute attr2 = attributeSource.getTransactionAttribute(method, TestService)

        then:
        attr1 != null
        attr2 != null
        attr1.is(attr2) // Same instance from cache
    }

    void "test caches null results for non-annotated methods"() {
        given:
        Method method = TestService.getMethod("notTransactional")

        when:
        TransactionAttribute attr1 = attributeSource.getTransactionAttribute(method, TestService)
        TransactionAttribute attr2 = attributeSource.getTransactionAttribute(method, TestService)

        then:
        attr1 == null
        attr2 == null
    }

    void "test handles Object methods"() {
        given:
        Method method = Object.getMethod("toString")

        when:
        TransactionAttribute attr = attributeSource.getTransactionAttribute(method, TestService)

        then:
        attr == null
    }

    void "test qualifier is always set to nativeTransaction"() {
        given:
        Method method1 = TestService.getMethod("methodLevelTransactional")
        Method method2 = TestService.getMethod("requiresNewTransaction")
        Method method3 = ClassLevelTestService.getMethod("someMethod")

        when:
        TransactionAttribute attr1 = attributeSource.getTransactionAttribute(method1, TestService)
        TransactionAttribute attr2 = attributeSource.getTransactionAttribute(method2, TestService)
        TransactionAttribute attr3 = attributeSource.getTransactionAttribute(method3, ClassLevelTestService)

        then:
        attr1.getQualifier() == "nativeTransaction"
        attr2.getQualifier() == "nativeTransaction"
        attr3.getQualifier() == "nativeTransaction"
    }

    // Test service classes

    static class TestService {

        @NativeTransactional
        void methodLevelTransactional() {}

        void notTransactional() {}

        @NativeTransactional(propagation = Propagation.REQUIRES_NEW)
        void requiresNewTransaction() {}

        @NativeTransactional(timeout = 30)
        void timeoutTransaction() {}

        @NativeTransactional(rollbackFor = IOException)
        void rollbackForTransaction() {}

        @NativeTransactional(noRollbackFor = IllegalArgumentException)
        void noRollbackForTransaction() {}
    }

    @NativeTransactional
    static class ClassLevelTestService {

        void someMethod() {}

        @NativeTransactional(propagation = Propagation.REQUIRES_NEW)
        void methodLevelOverride() {}
    }
}
