package example

import grails.mongodb.MongoEntity

class ServiceRequest implements MongoEntity<ServiceRequest> {
    String requestNumber
    String status
    String patientName
    Map<String, Object> metadata  // Large embedded document
    Date dateCreated
    Date lastUpdated
    Long version

    static constraints = {
        requestNumber nullable: false, unique: true
        status nullable: false
        patientName nullable: false
        metadata nullable: true
    }

    static mapping = {
        collection 'service_requests'
    }

    String toString() {
        return "ServiceRequest(${requestNumber}, ${status})"
    }
}
