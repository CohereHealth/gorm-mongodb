package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

@Integration
@NativeRollback
class ConfigurationValidationSpec extends Specification {

    MongoDatastore mongoDatastore

    void "test native transactions are enabled"() {
        when: "checking if native transactions are enabled"
        def enabled = mongoDatastore.nativeTransactionsEnabled

        then: "native transactions should be enabled"
        enabled == true
        println "Native transactions enabled: ${enabled}"
    }

    void "test can use withNativeTransaction"() {
        when: "using withNativeTransaction"
        def result = Provider.withNativeTransaction { session ->
            def provider = new Provider(
                firstName: "Config",
                lastName: "Test",
                age: 30
            ).save(failOnError: true)

            return provider
        }

        then: "transaction works"
        result != null
        result.id != null
        println "withNativeTransaction works: Provider ${result.id} created"
    }

    void "test transaction context is available"() {
        when: "creating entity in native transaction"
        def sessionActive = false
        Provider.withNativeTransaction { session ->
            def provider = new Provider(
                firstName: "Context",
                lastName: "Test",
                age: 25
            ).save(failOnError: true)

            sessionActive = session.hasActiveTransaction()
            println "Transaction context available, session active: ${sessionActive}"

            session != null && sessionActive
        }

        then: "session was active"
        sessionActive == true
    }
}
