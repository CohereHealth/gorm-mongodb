package example

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/**
 * Tests for multi-collection transaction rollback behavior.
 * These tests verify that transactions properly rollback across multiple collections.
 * No @NativeRollback annotation - we want to test actual database state after rollback.
 */
@Integration
class MultiCollectionTransactionBehaviorSpec extends Specification {

    def cleanup() {
        // Manual cleanup after each test since we're not using @NativeRollback
        ServiceRequest.collection.drop()
        CoverageSnapshot.collection.drop()
        AuditEvent.collection.drop()
    }

    void "test transaction isolation across collections"() {
        given: "initial state"
        def initialSRCount = ServiceRequest.count()
        def initialSnapshotCount = CoverageSnapshot.count()
        def initialAuditCount = AuditEvent.count()

        when: "creating in failed transaction"
        ServiceRequest.withNativeTransaction { session ->
            new ServiceRequest(
                requestNumber: "SR-005",
                status: "PENDING",
                patientName: "Charlie Brown"
            ).save(flush: true, failOnError: true)

            new CoverageSnapshot(
                serviceRequestNumber: "SR-005",
                snapshotType: "INITIAL",
                coverageData: [:],
                capturedAt: new Date()
            ).save(flush: true, failOnError: true)

            new AuditEvent(
                entityId: "test-id",
                entityType: "ServiceRequest",
                action: "CREATE",
                performedBy: "system",
                timestamp: new Date()
            ).save(flush: true, failOnError: true)

            throw new RuntimeException("Force rollback")
        }

        then: "exception is thrown"
        thrown(RuntimeException)

        and: "no records are persisted in any collection"
        ServiceRequest.count() == initialSRCount
        CoverageSnapshot.count() == initialSnapshotCount
        AuditEvent.count() == initialAuditCount
        ServiceRequest.findByRequestNumber("SR-005") == null
    }
}
