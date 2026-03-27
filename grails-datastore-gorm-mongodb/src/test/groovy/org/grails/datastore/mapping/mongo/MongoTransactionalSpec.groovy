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

    @Transactional
    def "test native transaction rollback"() {
        when:
        new TestPerson(name: "John").save(flush: true)
        def count = TestPerson.count()
        
        then:
        count == 1
        
        when: "transaction rolls back"
        throw new RuntimeException("Force rollback")
        
        then:
        thrown(RuntimeException)
        TestPerson.count() == 0 // Should be 0 due to rollback
    }
}

@Entity
class TestPerson {
    String name
}