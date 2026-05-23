package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import grails.gorm.tests.Person

class MongoNativeCodecEntityPersisterSpec extends GormDatastoreSpec {

    @Override
    Map getConfiguration() {
        [
            'grails.mongodb.nativeTransactions': true
        ]
    }

    void "test immediate insert execution in native transaction"() {
        when:
        def person = null
        Person.withNativeTransaction {
            person = new Person(firstName: "John", lastName: "Doe")
            person.save()
        }

        then:
        person.id != null
        Person.count() == 1
    }

    void "test immediate update execution in native transaction"() {
        given:
        def person = new Person(firstName: "John", lastName: "Doe").save(flush: true)

        when:
        Person.withNativeTransaction {
            person.firstName = "Jane"
            person.save()
        }

        then:
        session.clear()
        Person.get(person.id).firstName == "Jane"
    }

    void "test optimistic locking with versioned entity"() {
        given:
        def book = new VersionedBook(title: "Test Book").save(flush: true)
        def originalVersion = book.version

        when:
        VersionedBook.withNativeTransaction {
            book.title = "Updated Book"
            book.save()
        }

        then:
        book.version == originalVersion + 1
        session.clear()
        VersionedBook.get(book.id).title == "Updated Book"
    }

    void "test transaction rollback"() {
        when:
        Person.withNativeTransaction {
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
