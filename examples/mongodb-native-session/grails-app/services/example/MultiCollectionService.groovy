package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j

@Slf4j
@Transactional
class MultiCollectionService {

    /**
     * Creates a service request with coverage snapshot in a single native transaction
     * Demonstrates multi-collection transaction with coordinated commit
     */
    def createServiceRequestWithCoverage(Map srData, Map coverageData) {
        log.info("Creating service request with coverage snapshot in native transaction")

        return ServiceRequest.withNativeTransaction { session ->
            // Create service request
            def sr = new ServiceRequest(srData)
            sr.save(failOnError: true)
            log.info("Created ServiceRequest: ${sr.requestNumber}")

            // Create coverage snapshot
            def snapshot = new CoverageSnapshot(
                serviceRequestNumber: sr.requestNumber,
                snapshotType: coverageData.type,
                coverageData: coverageData.data,
                capturedAt: new Date()
            )
            snapshot.save(failOnError: true)
            log.info("Created CoverageSnapshot for: ${sr.requestNumber}")

            // Create audit event
            def audit = new AuditEvent(
                entityId: sr.id.toString(),
                entityType: 'ServiceRequest',
                action: 'CREATE',
                performedBy: 'system',
                changes: [status: sr.status],
                timestamp: new Date()
            )
            audit.save(failOnError: true)
            log.info("Created AuditEvent for: ${sr.requestNumber}")

            return [serviceRequest: sr, snapshot: snapshot, audit: audit]
        }
    }

    /**
     * Updates service request and creates coverage snapshot - tests rollback on failure
     */
    def updateServiceRequestWithFailure(String requestNumber, Map updates, boolean shouldFail = false) {
        log.info("Updating service request ${requestNumber} with shouldFail=${shouldFail}")

        return ServiceRequest.withNativeTransaction { session ->
            // Update service request
            def sr = ServiceRequest.findByRequestNumber(requestNumber)
            if (!sr) {
                throw new IllegalArgumentException("ServiceRequest not found: ${requestNumber}")
            }

            def oldStatus = sr.status
            updates.each { key, value -> sr[key] = value }
            sr.save(failOnError: true)
            log.info("Updated ServiceRequest: ${sr.requestNumber}")

            // Create coverage snapshot
            def snapshot = new CoverageSnapshot(
                serviceRequestNumber: sr.requestNumber,
                snapshotType: 'UPDATE',
                coverageData: [oldStatus: oldStatus, newStatus: sr.status],
                capturedAt: new Date()
            )
            snapshot.save(failOnError: true)
            log.info("Created CoverageSnapshot for update")

            // Create audit event
            def audit = new AuditEvent(
                entityId: sr.id.toString(),
                entityType: 'ServiceRequest',
                action: 'UPDATE',
                performedBy: 'system',
                changes: [from: oldStatus, to: sr.status],
                timestamp: new Date()
            )
            audit.save(failOnError: true)

            if (shouldFail) {
                log.info("Simulating failure - triggering rollback")
                throw new RuntimeException("Intentional failure after all writes")
            }

            return [serviceRequest: sr, snapshot: snapshot, audit: audit]
        }
    }

    /**
     * Demonstrates nested service calls within transaction
     */
    def createRequestWithNestedOperations(Map srData, List<Map> coverageDataList) {
        log.info("Creating request with nested coverage operations")

        return ServiceRequest.withNativeTransaction { session ->
            def sr = new ServiceRequest(srData)
            sr.save(failOnError: true)

            def snapshots = []
            coverageDataList.each { coverageData ->
                def snapshot = createCoverageSnapshotInternal(sr.requestNumber, coverageData)
                snapshots << snapshot
            }

            return [serviceRequest: sr, snapshots: snapshots]
        }
    }

    private def createCoverageSnapshotInternal(String requestNumber, Map coverageData) {
        // This runs within the parent transaction
        def snapshot = new CoverageSnapshot(
            serviceRequestNumber: requestNumber,
            snapshotType: coverageData.type,
            coverageData: coverageData.data,
            capturedAt: new Date()
        )
        snapshot.save(failOnError: true)
        return snapshot
    }
}
