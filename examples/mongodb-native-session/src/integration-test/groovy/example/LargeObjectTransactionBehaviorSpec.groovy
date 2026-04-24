package example

import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/**
 * Tests for large object transaction rollback behavior.
 * These tests verify that large objects are properly rolled back on transaction failure.
 * No @NativeRollback annotation - we want to test actual database state after rollback.
 */
@Integration
class LargeObjectTransactionBehaviorSpec extends Specification {

    LargeObjectService largeObjectService

    def cleanup() {
        // Manual cleanup after each test since we're not using @NativeRollback
        ServiceRequest.collection.drop()
        CoverageSnapshot.collection.drop()
    }

    void "test rollback with large objects"() {
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
        ServiceRequest.count() == old(ServiceRequest.count())
        ServiceRequest.findByRequestNumber("SR-ROLLBACK-TEST") == null
    }

    void "test large object transaction isolation"() {
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
        ServiceRequest.count() == old(ServiceRequest.count())
        ServiceRequest.findByRequestNumber("SR-ISOLATION-0") == null
        ServiceRequest.findByRequestNumber("SR-ISOLATION-1") == null
        ServiceRequest.findByRequestNumber("SR-ISOLATION-2") == null
    }
}
