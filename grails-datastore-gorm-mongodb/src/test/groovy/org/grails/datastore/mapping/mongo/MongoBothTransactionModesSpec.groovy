package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import org.springframework.transaction.annotation.Transactional

class MongoBothTransactionModesSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Product]
    }

    def "test selective native transactions without global config"() {
        when: "using withNativeTransaction selectively"
        def result = Product.withNativeTransaction { session ->
            new Product(name: "Laptop").save(flush: true)
            return [
                inNative: Product.isInNativeTransaction(),
                session: session != null
            ]
        }
        
        then:
        result.inNative == true
        result.session == true
        Product.count() == 1
        
        when: "using regular GORM methods outside native transaction"
        new Product(name: "Mouse").save(flush: true)
        
        then:
        !Product.isInNativeTransaction()
        Product.count() == 2
    }
    
    def "test mixed usage - selective within regular transaction"() {
        when: "regular transaction with selective native transaction inside"
        Product.withTransaction { status ->
            new Product(name: "Keyboard").save(flush: true)
            
            Product.withNativeTransaction { nativeSession ->
                new Product(name: "Monitor").save(flush: true)
                return Product.isInNativeTransaction()
            }
        }
        
        then:
        Product.count() == 2
    }
}

@Entity
class Product {
    String name
}