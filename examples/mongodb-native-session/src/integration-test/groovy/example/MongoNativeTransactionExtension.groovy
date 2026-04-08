package example

import com.mongodb.client.ClientSession
import org.spockframework.runtime.extension.IAnnotationDrivenExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.FeatureInfo
import org.spockframework.runtime.model.SpecInfo
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.MongoNativeTransactionContext
import grails.util.Holders

class MongoNativeTransactionExtension implements IAnnotationDrivenExtension<MongoNativeTransaction> {

    @Override
    void visitSpecAnnotation(MongoNativeTransaction annotation, SpecInfo spec) {
        spec.features.each { feature ->
            feature.addInterceptor(new MongoNativeTransactionInterceptor())
        }
    }

    @Override
    void visitFeatureAnnotation(MongoNativeTransaction annotation, FeatureInfo feature) {
        feature.addInterceptor(new MongoNativeTransactionInterceptor())
    }
}

class MongoNativeTransactionInterceptor implements IMethodInterceptor {

    @Override
    void intercept(IMethodInvocation invocation) throws Throwable {
        MongoDatastore datastore = Holders.applicationContext.getBean(MongoDatastore)
        ClientSession session = datastore.mongoClient.startSession()

        try {
            session.startTransaction()
            MongoNativeTransactionContext.pushNativeSession(session)
            invocation.proceed()
        } finally {
            if (session.hasActiveTransaction()) {
                session.abortTransaction()
            }
            MongoNativeTransactionContext.popNativeSession()
            session.close()
        }
    }
}
