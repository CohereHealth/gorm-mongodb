package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.core.OptimisticLockingException
import org.grails.datastore.mapping.mongo.engine.MongoNativeCodecEntityPersister

class MongoNativeCodecEntityPersisterSpec extends GormDatastoreSpec {

    void "test native persister is used for native transactions"() {
        when:
        def persister = null
        Person.withNativeTransaction { status ->
            def person = new Person(firstName: "John", lastName: "Doe")
            def session = DatastoreUtils.getSession(mongoDatastore, true)
            def entity = session.mappingContext.getPersistentEntity(Person.name)
            persister = session.getPersister(entity)
            person.save()
        }

        then:
        persister instanceof MongoNativeCodecEntityPersister
    }

    void "test immediate insert execution"() {
        when:
        def person = null
        Person.withTransaction { status ->
            person = new Person(firstName: "John", lastName: "Doe")
            person.save()
        }
        
        then:
        person.id != null
        Person.count() == 1
    }

    void "test immediate update execution"() {
        given:
        def person = new Person(firstName: "John", lastName: "Doe").save(flush: true)
        
        when:
        Person.withTransaction { status ->
            person.firstName = "Jane"
            person.save()
        }
        
        then:
        Person.get(person.id).firstName == "Jane"
    }

    void "test optimistic locking with versioned entity"() {
        given:
        def book = new VersionedBook(title: "Test Book").save(flush: true)
        def originalVersion = book.version
        
        when:
        VersionedBook.withTransaction { status ->
            book.title = "Updated Book"
            book.save()
        }
        
        then:
        book.version == originalVersion + 1
        VersionedBook.get(book.id).title == "Updated Book"
    }

    void "test optimistic locking exception"() {
        given:
        def book = new VersionedBook(title: "Test Book").save(flush: true)
        def book2 = VersionedBook.get(book.id)
        
        when:
        VersionedBook.withTransaction { status ->
            book.title = "Update 1"
            book.save()
            
            book2.title = "Update 2"
            book2.save()
        }
        
        then:
        thrown(OptimisticLockingException)
    }

    void "test transaction rollback"() {
        when:
        Person.withTransaction { status ->
            new Person(firstName: "John", lastName: "Doe").save()
            throw new RuntimeException("Test exception")
        }
        
        then:
        thrown(RuntimeException)
        Person.count() == 0
    }

    @Override
    List getDomainClasses() {
        [Person, VersionedBook]
    }
}

@Entity
class VersionedBook {
    String id
    String title
    Long version

    static mapping = {
        collection "versioned_books"
        version true
    }
}