package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.springframework.transaction.annotation.Transactional
import spock.lang.Specification

class MongoTransactionalSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [TestPerson]
    }
    
    @Override
    Map getConfiguration() {
        [
            'grails.mongodb.nativeTransactions': true
        ]
    }

    def "test native transaction rollback"() {
        when: "transaction rolls back on exception"
        try {
            TestPerson.withNativeTransaction {
                new TestPerson(name: "John").save()
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException e) {
            // Expected
        }

        then:
        TestPerson.count() == 0 // Should be 0 due to rollback
    }
}

@Entity
class TestPerson {
    String name
}