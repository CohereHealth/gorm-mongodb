package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

@Integration
@NativeRollback
class ConfigurationValidationSpec extends Specification {

    MongoDatastore mongoDatastore

    void "test native transactions work without global config"() {
        when: "checking native transaction support"
        def enabled = mongoDatastore.nativeTransactionsEnabled

        then: "native transactions are not globally enabled (we use withNativeTransaction explicitly)"
        enabled == false
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
    }

    void "test transaction allows entity persistence"() {
        when: "creating entity in native transaction"
        def result = Provider.withNativeTransaction { session ->
            new Provider(
                firstName: "Context",
                lastName: "Test",
                age: 25
            ).save(failOnError: true)
        }

        then: "entity is persisted successfully"
        result != null
        result.id != null

        and: "queried entity matches created entity"
        def found = Provider.findByFirstName("Context")
        found != null
        found.id == result.id
    }
}
