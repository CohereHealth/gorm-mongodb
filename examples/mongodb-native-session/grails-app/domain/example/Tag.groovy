package example

import grails.gorm.annotation.Entity

@Entity
class Tag {
    String name
    String description

    static constraints = {
        name nullable: false, blank: false, unique: true, maxSize: 255
        description nullable: true, maxSize: 1000
    }

    static mapping = {
        collection 'tags'
    }
}
