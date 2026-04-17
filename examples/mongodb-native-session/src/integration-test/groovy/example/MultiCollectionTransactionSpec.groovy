package example

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

@Integration
class MultiCollectionTransactionSpec extends Specification {

    MultiCollectionService multiCollectionService

    def cleanup() {
        ServiceRequest.collection.drop()
        CoverageSnapshot.collection.drop()
        AuditEvent.collection.drop()
    }

    void "test create service request with coverage snapshot and audit"() {
        given: "service request and coverage data"
        def srData = [
            requestNumber: "SR-001",
            status: "PENDING",
            patientName: "John Doe"
        ]
        def coverageData = [
            type: "INITIAL",
            data: [planName: "Premium Plan", memberId: "MEM123"]
        ]

        when: "creating service request with coverage"
        def result = multiCollectionService.createServiceRequestWithCoverage(srData, coverageData)

        then: "all entities are created in 3 different collections"
        result.serviceRequest != null
        result.snapshot != null
        result.audit != null

        and: "service request is persisted"
        ServiceRequest.count() == 1
        ServiceRequest.findByRequestNumber("SR-001") != null

        and: "coverage snapshot is persisted"
        CoverageSnapshot.count() == 1
        CoverageSnapshot.findByServiceRequestNumber("SR-001") != null

        and: "audit event is persisted"
        AuditEvent.count() == 1
        def audit = AuditEvent.findByEntityType("ServiceRequest")
        audit.action == "CREATE"
        audit.performedBy == "system"
    }

    void "test multi-collection transaction rollback on failure"() {
        given: "service request data"
        def srData = [
            requestNumber: "SR-002",
            status: "PENDING",
            patientName: "Jane Smith"
        ]
        def updates = [status: "APPROVED"]

        and: "initial service request exists"
        multiCollectionService.createServiceRequestWithCoverage(srData, [type: "INITIAL", data: [:]])
        def initialSRCount = ServiceRequest.count()
        def initialSnapshotCount = CoverageSnapshot.count()
        def initialAuditCount = AuditEvent.count()

        when: "updating with forced failure"
        multiCollectionService.updateServiceRequestWithFailure("SR-002", updates, true)

        then: "exception is thrown"
        thrown(RuntimeException)

        and: "failed transaction is rolled back atomically"
        ServiceRequest.count() == initialSRCount
        CoverageSnapshot.count() == initialSnapshotCount
        AuditEvent.count() == initialAuditCount

        and: "service request update was rolled back"
        def sr = ServiceRequest.findByRequestNumber("SR-002")
        sr.status == "PENDING"
    }

    void "test multi-collection transaction successful update"() {
        given: "existing service request"
        def srData = [
            requestNumber: "SR-003",
            status: "PENDING",
            patientName: "Bob Johnson"
        ]
        multiCollectionService.createServiceRequestWithCoverage(srData, [type: "INITIAL", data: [:]])
        def initialSnapshotCount = CoverageSnapshot.count()
        def initialAuditCount = AuditEvent.count()

        when: "updating without failure"
        def result = multiCollectionService.updateServiceRequestWithFailure("SR-003", [status: "APPROVED"], false)

        then: "update succeeds"
        result.serviceRequest.status == "APPROVED"

        and: "new snapshot is created"
        CoverageSnapshot.count() == initialSnapshotCount + 1
        def updateSnapshot = CoverageSnapshot.findBySnapshotType("UPDATE")
        updateSnapshot != null
        updateSnapshot.coverageData.oldStatus == "PENDING"
        updateSnapshot.coverageData.newStatus == "APPROVED"

        and: "new audit event is created"
        AuditEvent.count() == initialAuditCount + 1
        def updateAudit = AuditEvent.findByAction("UPDATE")
        updateAudit != null
        updateAudit.changes.from == "PENDING"
        updateAudit.changes.to == "APPROVED"
    }

    void "test nested operations within transaction"() {
        given: "service request data with multiple coverage snapshots"
        def srData = [
            requestNumber: "SR-004",
            status: "PENDING",
            patientName: "Alice Cooper"
        ]
        def coverageDataList = [
            [type: "ELIGIBILITY", data: [eligible: true]],
            [type: "BENEFITS", data: [deductible: 1000]],
            [type: "AUTHORIZATION", data: [approved: true]]
        ]

        when: "creating request with nested operations"
        def result = multiCollectionService.createRequestWithNestedOperations(srData, coverageDataList)

        then: "service request is created"
        result.serviceRequest != null
        result.serviceRequest.requestNumber == "SR-004"

        and: "all snapshots are created"
        result.snapshots.size() == 3
        CoverageSnapshot.countByServiceRequestNumber("SR-004") == 3

        and: "each snapshot has correct type"
        def types = CoverageSnapshot.findAllByServiceRequestNumber("SR-004")*.snapshotType
        types.containsAll(["ELIGIBILITY", "BENEFITS", "AUTHORIZATION"])
    }

    void "test optimistic locking with multi-collection update"() {
        given: "existing service request with version"
        def srData = [
            requestNumber: "SR-006",
            status: "PENDING",
            patientName: "Diana Prince"
        ]
        def result = multiCollectionService.createServiceRequestWithCoverage(srData, [type: "INITIAL", data: [:]])
        def originalVersion = result.serviceRequest.version

        when: "updating in native transaction"
        def updateResult = multiCollectionService.updateServiceRequestWithFailure(
            "SR-006",
            [status: "APPROVED"],
            false
        )

        then: "version is incremented"
        updateResult.serviceRequest.version == originalVersion + 1

        and: "changes are persisted"
        def reloaded = ServiceRequest.findByRequestNumber("SR-006")
        reloaded.version == originalVersion + 1
        reloaded.status == "APPROVED"
    }
}
