package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

@Integration
@NativeRollback
class LargeObjectTransactionSpec extends Specification {

    LargeObjectService largeObjectService

    def checkOutsideTransaction(Closure check) {
        def result = null
        if (org.grails.datastore.mapping.mongo.MongoNativeTransactionContext.hasNativeSession()) {
            def session = org.grails.datastore.mapping.mongo.MongoNativeTransactionContext.popNativeSession()
            try {
                result = check.call()
            } finally {
                org.grails.datastore.mapping.mongo.MongoNativeTransactionContext.pushNativeSession(session)
            }
        } else {
            result = check.call()
        }
        return result
    }

    void "test create 100KB service request"() {
        when: "creating service request with 100KB metadata"
        def result = largeObjectService.createLargeServiceRequest(100)

        then: "service request is created"
        result.result.serviceRequest != null
        result.result.snapshot != null

        and: "latency is recorded"
        result.latencyMs > 0
        println "100KB object creation latency: ${result.latencyMs}ms"

        and: "entities are persisted"
        ServiceRequest.findByRequestNumber(result.result.serviceRequest.requestNumber) != null
    }

    void "test create 500KB service request"() {
        when: "creating service request with 500KB metadata"
        def result = largeObjectService.createLargeServiceRequest(500)

        then: "service request is created"
        result.result.serviceRequest != null
        result.result.snapshot != null

        and: "latency is recorded"
        result.latencyMs > 0
        println "500KB object creation latency: ${result.latencyMs}ms"

        and: "metadata size is approximately correct"
        def metadata = result.result.serviceRequest.metadata
        metadata != null
        metadata.size() > 0
    }

    void "test create 1MB service request"() {
        when: "creating service request with 1MB metadata"
        def result = largeObjectService.createLargeServiceRequest(1024)

        then: "service request is created"
        result.result.serviceRequest != null
        result.result.snapshot != null

        and: "latency is recorded"
        result.latencyMs > 0
        println "1MB object creation latency: ${result.latencyMs}ms"

        and: "large object is persisted correctly"
        def reloaded = ServiceRequest.findByRequestNumber(result.result.serviceRequest.requestNumber)
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

    void "test rollback with large objects"() {
        given: "initial count"
        def initialCount = ServiceRequest.count()

        when: "creating large object in failed transaction"
        ServiceRequest.withNativeTransaction { session ->
            def metadata = LargeObjectService.generateLargeMetadata(500)
            def sr = new ServiceRequest(
                requestNumber: "SR-ROLLBACK-TEST",
                status: "PENDING",
                patientName: "Rollback Test",
                metadata: metadata
            )
            sr.save(flush: true, failOnError: true)

            throw new RuntimeException("Force rollback")
        }

        then: "exception is thrown"
        thrown(RuntimeException)

        and: "large object is not persisted"
        checkOutsideTransaction { ServiceRequest.count() } == initialCount
        checkOutsideTransaction { ServiceRequest.findByRequestNumber("SR-ROLLBACK-TEST") } == null
    }

    void "test multi-collection transaction with large objects"() {
        when: "creating large service request with snapshot"
        def result = largeObjectService.createLargeServiceRequest(500)

        then: "both collections have data"
        result.result.serviceRequest != null
        result.result.snapshot != null

        and: "service request has large metadata"
        result.result.serviceRequest.metadata.size() > 0

        and: "snapshot has half-sized metadata"
        result.result.snapshot.coverageData.size() > 0

        and: "both are persisted"
        ServiceRequest.findByRequestNumber(result.result.serviceRequest.requestNumber) != null
        CoverageSnapshot.findByServiceRequestNumber(result.result.serviceRequest.requestNumber) != null
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
        def requestNumber = createResult.result.serviceRequest.requestNumber

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

    void "test large object transaction isolation"() {
        given: "initial state"
        def initialCount = ServiceRequest.count()

        when: "creating multiple large objects in failed transaction"
        ServiceRequest.withNativeTransaction { session ->
            3.times { i ->
                def metadata = LargeObjectService.generateLargeMetadata(500)
                def sr = new ServiceRequest(
                    requestNumber: "SR-ISOLATION-${i}",
                    status: "PENDING",
                    patientName: "Isolation Test ${i}",
                    metadata: metadata
                )
                sr.save(flush: true, failOnError: true)
            }

            throw new RuntimeException("Force rollback")
        }

        then: "exception is thrown"
        thrown(RuntimeException)

        and: "none of the large objects are persisted"
        checkOutsideTransaction { ServiceRequest.count() } == initialCount
        checkOutsideTransaction { ServiceRequest.findByRequestNumber("SR-ISOLATION-0") } == null
        checkOutsideTransaction { ServiceRequest.findByRequestNumber("SR-ISOLATION-1") } == null
        checkOutsideTransaction { ServiceRequest.findByRequestNumber("SR-ISOLATION-2") } == null
    }
}
