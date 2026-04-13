package example

class FacilityAddress {
    String street
    String city
    String state
    String zipCode

    static constraints = {
        street nullable: false, blank: false
        city nullable: false, blank: false
        state nullable: false, blank: false
        zipCode nullable: false, blank: false
    }
}
