package example

import grails.gorm.annotation.Entity

@Entity
class Tag {
    String name
    String description

    static constraints = {
        name nullable: false, blank: false, unique: true
        description nullable: true
    }

    static mapping = {
        collection 'tags'
    }
}
