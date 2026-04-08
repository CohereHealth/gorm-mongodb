package example

import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import org.grails.datastore.mapping.mongo.MongoSessionHolder
import org.grails.datastore.mapping.mongo.NativeRollback
import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.SpecInfo
import com.mongodb.client.ClientSession
import grails.util.Holders
import org.springframework.transaction.support.TransactionSynchronizationManager

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

class NativeRollbackInterceptor implements IMethodInterceptor {

    @Override
    void intercept(IMethodInvocation invocation) throws Throwable {
        MongoDatastore datastore = Holders.applicationContext.getBean(MongoDatastore)
        ClientSession session = datastore.mongoClient.startSession()

        try {
            session.startTransaction()
            MongoNativeTransactionContext.pushNativeSession(session)

            // Bind MongoSessionHolder so getCurrentSession() works and doGetTransaction() reuses it
            def sessionHolder = new MongoSessionHolder(session)
            TransactionSynchronizationManager.bindResource(datastore, sessionHolder)

            // Connect a GORM session (will be MongoNativeCodecSession)
            datastore.connect()

            invocation.proceed()
        } finally {
            if (session.hasActiveTransaction()) {
                session.abortTransaction()
            }
            MongoNativeTransactionContext.popNativeSession()
            TransactionSynchronizationManager.unbindResourceIfPossible(datastore)
            session.close()
        }
    }
}
