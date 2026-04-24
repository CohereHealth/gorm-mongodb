package org.grails.datastore.mapping.mongo

import com.mongodb.client.ClientSession
import grails.util.Holders
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.SpecInfo
import org.springframework.transaction.support.TransactionSynchronizationManager

@CompileStatic
class NativeRollbackExtension implements IGlobalExtension {

    @Override
    void visitSpec(SpecInfo spec) {
        if (spec.getAnnotation(NativeRollback) != null) {
            spec.allFeatures.each { feature ->
                feature.addIterationInterceptor(new NativeRollbackInterceptor())
            }
        }
    }
}

@Slf4j
@CompileStatic
class NativeRollbackInterceptor implements IMethodInterceptor {

    @Override
    void intercept(IMethodInvocation invocation) throws Throwable {
        MongoDatastore datastore = Holders.applicationContext.getBean(MongoDatastore)
        ClientSession session = datastore.mongoClient.startSession()

        // Detect pre-existing leaked sessions
        if (MongoNativeTransactionContext.hasNativeSession()) {
            log.warn("Leaked native session detected before test [{}#{}]. Stack depth: {}. Clearing.",
                invocation.spec.name, invocation.feature.name,
                MongoNativeTransactionContext.sessionStackDepth)
            MongoNativeTransactionContext.clearNativeSession()
        }

        // Unbind any existing resource so we start clean
        Object suspendedResource = TransactionSynchronizationManager.getResource(datastore)
        if (suspendedResource != null) {
            TransactionSynchronizationManager.unbindResource(datastore)
        }

        try {
            session.startTransaction()
            MongoNativeTransactionContext.pushNativeSession(session)

            def sessionHolder = new MongoSessionHolder(datastore.connect(), session)
            TransactionSynchronizationManager.bindResource(datastore, sessionHolder)

            invocation.proceed()
        } finally {
            if (session.hasActiveTransaction()) {
                session.abortTransaction()
            }
            MongoNativeTransactionContext.popNativeSession()
            TransactionSynchronizationManager.unbindResourceIfPossible(datastore)
            session.close()

            // Detect sessions leaked during this test
            if (MongoNativeTransactionContext.hasNativeSession()) {
                log.warn("Native session leak after test [{}#{}]. Stack depth: {}. Clearing.",
                    invocation.spec.name, invocation.feature.name,
                    MongoNativeTransactionContext.sessionStackDepth)
                MongoNativeTransactionContext.clearNativeSession()
            }
        }
    }
}
