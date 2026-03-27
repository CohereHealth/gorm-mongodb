package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec

class MongoNestedTransactionInheritanceSpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [Item]
    }

    def "test native parent forces native children"() {
        when: "native parent with regular child calls"
        def result = Item.withNativeTransaction { nativeSession ->
            new Item(name: "Parent").save(flush: true)
            
            // Child call using withTransaction should inherit native
            def childResult = Item.withTransaction { status ->
                new Item(name: "Child").save(flush: true)
                return Item.isInNativeTransaction()
            }
            
            return [
                parentNative: Item.isInNativeTransaction(),
                childNative: childResult
            ]
        }
        
        then:
        result.parentNative == true
        result.childNative == true
        Item.count() == 2
    }
    
    def "test regular parent keeps regular children"() {
        when: "regular parent with native child call"
        def result = Item.withTransaction { status ->
            new Item(name: "RegularParent").save(flush: true)
            
            // Child call using withNativeTransaction should stay regular
            def childResult = Item.withNativeTransaction { nativeSession ->
                new Item(name: "RegularChild").save(flush: true)
                return Item.isInNativeTransaction()
            }
            
            return [
                parentNative: Item.isInNativeTransaction(),
                childNative: childResult
            ]
        }
        
        then:
        result.parentNative == false
        result.childNative == false
        Item.count() == 4 // 2 from previous test + 2 new
    }
    
    def "test deep nesting inheritance"() {
        when: "deep nested calls inherit parent transaction type"
        def result = Item.withNativeTransaction { session1 ->
            new Item(name: "Level1").save(flush: true)
            
            Item.withTransaction { status ->
                new Item(name: "Level2").save(flush: true)
                
                Item.withNativeTransaction { session2 ->
                    new Item(name: "Level3").save(flush: true)
                    
                    return [
                        level1Native: session1 != null,
                        level2Native: Item.isInNativeTransaction(),
                        level3Native: Item.isInNativeTransaction(),
                        sameSession: session1 == session2
                    ]
                }
            }
        }
        
        then:
        result.level1Native == true
        result.level2Native == true
        result.level3Native == true
        result.sameSession == true
        Item.count() == 7 // 4 from previous tests + 3 new
    }
}

@Entity
class Item {
    String name
}