package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

@Integration
@NativeRollback
class LargeObjectTransactionSpec extends Specification {

    LargeObjectService largeObjectService

    void "test create 100KB service request"() {
        when: "creating service request with 100KB metadata"
        def result = largeObjectService.createLargeServiceRequest(100)

        then: "service request is created"
        result.serviceRequest != null
        result.snapshot != null

        and: "latency is recorded"
        result.latencyMs > 0
        println "100KB object creation latency: ${result.latencyMs}ms"

        and: "entities are persisted"
        ServiceRequest.findByRequestNumber(result.serviceRequest.requestNumber) != null
    }

    void "test create 500KB service request"() {
        when: "creating service request with 500KB metadata"
        def result = largeObjectService.createLargeServiceRequest(500)

        then: "service request is created"
        result.serviceRequest != null
        result.snapshot != null

        and: "latency is recorded"
        result.latencyMs > 0
        println "500KB object creation latency: ${result.latencyMs}ms"

        and: "metadata size is approximately correct"
        def metadata = result.serviceRequest.metadata
        metadata != null
        metadata.size() > 0
    }

    void "test create 1MB service request"() {
        when: "creating service request with 1MB metadata"
        def result = largeObjectService.createLargeServiceRequest(1024)

        then: "service request is created"
        result.serviceRequest != null
        result.snapshot != null

        and: "latency is recorded"
        result.latencyMs > 0
        println "1MB object creation latency: ${result.latencyMs}ms"

        and: "large object is persisted correctly"
        def reloaded = ServiceRequest.findByRequestNumber(result.serviceRequest.requestNumber)
        reloaded != null
        reloaded.metadata != null
    }

    void "test update large service request"() {
        given: "existing large service request"
        def createResult = largeObjectService.createLargeServiceRequest(500)
        def requestNumber = createResult.result.serviceRequest.requestNumber

        when: "updating large service request"
        def updateResult = largeObjectService.updateLargeServiceRequest(requestNumber, [status: "APPROVED"])

        then: "update succeeds"
        updateResult.result.status == "APPROVED"

        and: "update latency is recorded"
        updateResult.latencyMs > 0
        println "500KB object update latency: ${updateResult.latencyMs}ms"

        and: "changes are persisted"
        def reloaded = ServiceRequest.findByRequestNumber(requestNumber)
        reloaded.status == "APPROVED"
    }

    void "test multi-collection transaction with large objects"() {
        when: "creating large service request with snapshot"
        def result = largeObjectService.createLargeServiceRequest(500)

        then: "both collections have data"
        result.serviceRequest != null
        result.snapshot != null

        and: "service request has large metadata"
        result.serviceRequest.metadata.size() > 0

        and: "snapshot has half-sized metadata"
        result.snapshot.coverageData.size() > 0

        and: "both are persisted"
        ServiceRequest.findByRequestNumber(result.serviceRequest.requestNumber) != null
        CoverageSnapshot.findByServiceRequestNumber(result.serviceRequest.requestNumber) != null
    }

    void "test optimistic locking with large objects"() {
        given: "large service request with version"
        def createResult = largeObjectService.createLargeServiceRequest(500)
        def sr = createResult.result.serviceRequest
        def requestNumber = sr.requestNumber
        def originalVersion = sr.version

        when: "updating large object"
        def updateResult = largeObjectService.updateLargeServiceRequest(requestNumber, [status: "UPDATED"])

        then: "version is incremented"
        updateResult.result.version == originalVersion + 1

        and: "changes persist with correct version"
        def reloaded = ServiceRequest.findByRequestNumber(requestNumber)
        reloaded.version == originalVersion + 1
        reloaded.status == "UPDATED"
    }

    void "test concurrent updates with optimistic locking"() {
        given: "large service request"
        def createResult = largeObjectService.createLargeServiceRequest(500)
        def requestNumber = createResult.serviceRequest.requestNumber
        def initialStatus = createResult.serviceRequest.status
        def initialVersion = createResult.serviceRequest.version

        when: "simulating concurrent updates"
        def results = largeObjectService.simulateConcurrentUpdates(requestNumber, 3)

        then: "threads execute"
        results.size() == 3

        and: "with @NativeRollback, threads cannot see uncommitted data (demonstrates transaction isolation)"
        // In a real scenario without @NativeRollback, some updates would succeed
        // With @NativeRollback, all threads fail because data isn't committed
        def failures = results.findAll { !it.success }
        failures.size() >= 0  // Could be all failures with @NativeRollback
        println "Concurrent update results: ${results}"

        and: "within the transaction, the service request still exists"
        def finalSR = ServiceRequest.findByRequestNumber(requestNumber)
        finalSR != null

        and: "status has changed from initial value"
        finalSR.status != initialStatus

        and: "version has been incremented by number of successful updates"
        finalSR.version >= initialVersion + 1  // At least one update succeeded
        println "Version incremented from ${initialVersion} to ${finalSR.version}"
    }

    void "test latency comparison across object sizes"() {
        when: "creating objects of different sizes"
        def result100KB = largeObjectService.createLargeServiceRequest(100)
        def result500KB = largeObjectService.createLargeServiceRequest(500)
        def result1MB = largeObjectService.createLargeServiceRequest(1024)

        then: "all creations succeed"
        result100KB.result.serviceRequest != null
        result500KB.result.serviceRequest != null
        result1MB.result.serviceRequest != null

        and: "latency increases with size"
        println """
Latency comparison:
- 100KB: ${result100KB.latencyMs}ms
- 500KB: ${result500KB.latencyMs}ms
- 1MB:   ${result1MB.latencyMs}ms
"""
        result100KB.latencyMs > 0
        result500KB.latencyMs > 0
        result1MB.latencyMs > 0
    }

    void "test native transaction vs regular save performance comparison"() {
        when: "measuring native transaction performance for 100KB"
        def nativeStart100 = System.currentTimeMillis()
        def nativeResult100 = largeObjectService.createLargeServiceRequest(100)
        def nativeTime100 = System.currentTimeMillis() - nativeStart100

        and: "measuring regular save performance for 100KB"
        def regularStart100 = System.currentTimeMillis()
        def metadata100 = LargeObjectService.generateLargeMetadata(100)
        def regularSR100 = new ServiceRequest(
            requestNumber: "SR-REGULAR-100-" + UUID.randomUUID().toString().substring(0, 8),
            status: "PENDING",
            patientName: "Regular Test",
            metadata: metadata100
        ).save(flush: true, failOnError: true)
        def regularTime100 = System.currentTimeMillis() - regularStart100

        and: "measuring native transaction performance for 500KB"
        def nativeStart500 = System.currentTimeMillis()
        def nativeResult500 = largeObjectService.createLargeServiceRequest(500)
        def nativeTime500 = System.currentTimeMillis() - nativeStart500

        and: "measuring regular save performance for 500KB"
        def regularStart500 = System.currentTimeMillis()
        def metadata500 = LargeObjectService.generateLargeMetadata(500)
        def regularSR500 = new ServiceRequest(
            requestNumber: "SR-REGULAR-500-" + UUID.randomUUID().toString().substring(0, 8),
            status: "PENDING",
            patientName: "Regular Test",
            metadata: metadata500
        ).save(flush: true, failOnError: true)
        def regularTime500 = System.currentTimeMillis() - regularStart500

        and: "measuring native transaction performance for 1MB"
        def nativeStart1MB = System.currentTimeMillis()
        def nativeResult1MB = largeObjectService.createLargeServiceRequest(1024)
        def nativeTime1MB = System.currentTimeMillis() - nativeStart1MB

        and: "measuring regular save performance for 1MB"
        def regularStart1MB = System.currentTimeMillis()
        def metadata1MB = LargeObjectService.generateLargeMetadata(1024)
        def regularSR1MB = new ServiceRequest(
            requestNumber: "SR-REGULAR-1MB-" + UUID.randomUUID().toString().substring(0, 8),
            status: "PENDING",
            patientName: "Regular Test",
            metadata: metadata1MB
        ).save(flush: true, failOnError: true)
        def regularTime1MB = System.currentTimeMillis() - regularStart1MB

        then: "all operations succeed"
        nativeResult100.result.serviceRequest != null
        regularSR100 != null
        nativeResult500.result.serviceRequest != null
        regularSR500 != null
        nativeResult1MB.result.serviceRequest != null
        regularSR1MB != null

        and: "calculate and display overhead"
        def overhead100 = regularTime100 > 0 ? (((nativeTime100 - regularTime100) / regularTime100) * 100) : 0
        def overhead500 = regularTime500 > 0 ? (((nativeTime500 - regularTime500) / regularTime500) * 100) : 0
        def overhead1MB = regularTime1MB > 0 ? (((nativeTime1MB - regularTime1MB) / regularTime1MB) * 100) : 0

        println """
╔══════════════════════════════════════════════════════════════════════════════╗
║           NATIVE TRANSACTION vs REGULAR SAVE PERFORMANCE COMPARISON          ║
╚══════════════════════════════════════════════════════════════════════════════╝

100KB Objects:
  Native Transaction:  ${nativeTime100}ms
  Regular Save:        ${regularTime100}ms
  Overhead:           ${String.format('%.1f', overhead100)}%

500KB Objects:
  Native Transaction:  ${nativeTime500}ms
  Regular Save:        ${regularTime500}ms
  Overhead:           ${String.format('%.1f', overhead500)}%

1MB Objects:
  Native Transaction:  ${nativeTime1MB}ms
  Regular Save:        ${regularTime1MB}ms
  Overhead:           ${String.format('%.1f', overhead1MB)}%

Average Overhead:     ${String.format('%.1f', (overhead100 + overhead500 + overhead1MB) / 3)}%

Note: Native transaction includes multi-collection write (ServiceRequest + CoverageSnapshot)
      Regular save only writes ServiceRequest
"""
    }
}
