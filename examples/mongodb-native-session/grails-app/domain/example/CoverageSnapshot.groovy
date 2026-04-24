package example

import grails.mongodb.MongoEntity

class CoverageSnapshot implements MongoEntity<CoverageSnapshot> {
    String serviceRequestNumber
    String snapshotType
    Map<String, Object> coverageData  // Large embedded document
    Date capturedAt
    Date dateCreated
    Date lastUpdated
    Long version

    static constraints = {
        serviceRequestNumber nullable: false
        snapshotType nullable: false
        coverageData nullable: true
        capturedAt nullable: false
    }

    static mapping = {
        collection 'coverage_snapshots'
    }

    String toString() {
        return "CoverageSnapshot(${serviceRequestNumber}, ${snapshotType})"
    }
}
