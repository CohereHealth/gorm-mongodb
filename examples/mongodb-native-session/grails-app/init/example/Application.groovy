package example

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration
import org.grails.datastore.mapping.mongo.config.MongoNativeTransactionAopConfiguration
import org.springframework.context.annotation.Import

/**
 * Import MongoNativeTransactionAopConfiguration to enable @NativeTransactional annotation support.
 */
@Import(MongoNativeTransactionAopConfiguration)
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
        GrailsApp.run(Application, args)
    }
}