package example

import org.spockframework.runtime.extension.IAnnotationDrivenExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.FeatureInfo
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.springframework.context.ApplicationContext
import grails.util.Holders

class MongoNativeTransactionExtension implements IAnnotationDrivenExtension<MongoNativeTransaction> {
    
    @Override
    void visitFeatureAnnotation(MongoNativeTransaction annotation, FeatureInfo feature) {
        feature.addInterceptor(new MongoNativeTransactionInterceptor())
    }
}

class MongoNativeTransactionInterceptor implements IMethodInterceptor {
    
    @Override
    void intercept(IMethodInvocation invocation) throws Throwable {
        ApplicationContext ctx = Holders.applicationContext
        MongoDatastore datastore = ctx.getBean(MongoDatastore)
        
        datastore.withNativeTransaction { session ->
            invocation.proceed()
            // Transaction will rollback automatically after test completes
        }
    }
}