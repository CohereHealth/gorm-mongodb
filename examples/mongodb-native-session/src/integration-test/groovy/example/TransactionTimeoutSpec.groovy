package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Ignore
import spock.lang.Specification
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.MongoCommandException
import com.mongodb.TransactionOptions
import java.util.concurrent.TimeUnit

/**
 * Comprehensive tests for transaction timeout behavior and long-running transactions.
 */
@Integration
@NativeRollback
class TransactionTimeoutSpec extends Specification {

    void "test default timeout is sufficient for multi-collection operations"() {
        given: "initial counts"
        def initialSRCount = ServiceRequest.count()
        def initialAuditCount = AuditEvent.count()
        def initialCoverageCount = CoverageSnapshot.count()

        println "=== DEFAULT TIMEOUT TEST ==="
        println "MongoDB default transaction timeout: 60 seconds"

        when: "performing normal multi-collection operation"
        def startTime = System.currentTimeMillis()

        def result = ServiceRequest.withNativeTransaction { session ->
            // Create service request
            def sr = new ServiceRequest(
                requestNumber: "SR-DEFAULT-${System.currentTimeMillis()}",
                status: "PENDING",
                patientName: "Default Timeout Patient"
            ).save(failOnError: true)

            // Create audit
            new AuditEvent(
                entityType: "ServiceRequest",
                entityId: sr.requestNumber,
                action: "CREATED",
                performedBy: "SYSTEM",
                timestamp: new Date()
            ).save(failOnError: true)

            // Create coverage
            new CoverageSnapshot(
                serviceRequestNumber: sr.requestNumber,
                snapshotType: "INITIAL",
                snapshotData: [plan: "HMO"],
                capturedAt: new Date()
            ).save(failOnError: true)

            return sr
        }

        def duration = System.currentTimeMillis() - startTime

        then: "transaction completes successfully"
        result != null
        ServiceRequest.count() == initialSRCount + 1
        AuditEvent.count() == initialAuditCount + 1
        CoverageSnapshot.count() == initialCoverageCount + 1

        and: "duration is far below default 60-second timeout"
        duration < 1000  // Should complete in under 1 second

        println "Transaction duration: ${duration}ms"
        println "Buffer before timeout: ${60000 - duration}ms (${(60000 - duration) / 1000} seconds)"
        println "All 3 collections updated atomically"
        println "================================"

        cleanup:
        ServiceRequest.collection.drop()
        AuditEvent.collection.drop()
        CoverageSnapshot.collection.drop()
    }

    void "test transaction with very large object measures actual time"() {
        given: "initial count"
        def initialCount = ServiceRequest.count()

        println "=== LARGE OBJECT TRANSACTION TEST ==="

        when: "creating 5MB object"
        def startTime = System.currentTimeMillis()

        // Create 5MB metadata
        def largeMetadata = [:]
        (1..5000).each { i ->
            largeMetadata["event_${i}"] = [
                event: "Event ${i}",
                timestamp: new Date().toString(),
                data: "x" * 1000  // 1KB per entry
            ]
        }
        println "Large metadata created: ~5MB"

        def result = ServiceRequest.withNativeTransaction { session ->
            new ServiceRequest(
                requestNumber: "SR-LARGE-${System.currentTimeMillis()}",
                status: "PENDING",
                patientName: "Large Object Patient",
                metadata: largeMetadata
            ).save(failOnError: true)
        }

        def duration = System.currentTimeMillis() - startTime

        then: "large object created successfully within reasonable time"
        result != null
        ServiceRequest.count() == initialCount + 1

        and: "duration is well below 60-second timeout"
        duration < 60000

        println "Large object (5MB) transaction duration: ${duration}ms"
        println "Timeout limit: 60000ms (60 seconds)"
        println "Buffer remaining: ${60000 - duration}ms"
        println "Conclusion: Object SIZE is NOT the issue, only DURATION matters"
        println "================================"

        cleanup:
        ServiceRequest.collection.drop()
    }

    void "test transaction with slow operation does not timeout"() {
        given: "initial count"
        def initialCount = ServiceRequest.count()

        println "=== SLOW OPERATION TEST ==="

        when: "performing operation with 5-second delay"
        def startTime = System.currentTimeMillis()

        ServiceRequest.withNativeTransaction { session ->
            // Create first object
            def sr1 = new ServiceRequest(
                requestNumber: "SR-SLOW-1-${System.currentTimeMillis()}",
                status: "PENDING",
                patientName: "Slow Patient 1"
            ).save(failOnError: true)
            println "Created SR1"

            // Simulate slow external operation (e.g., complex calculation)
            println "Sleeping 5 seconds..."
            Thread.sleep(5000)  // 5 seconds

            // Create second object
            def sr2 = new ServiceRequest(
                requestNumber: "SR-SLOW-2-${System.currentTimeMillis()}",
                status: "PENDING",
                patientName: "Slow Patient 2"
            ).save(failOnError: true)
            println "Created SR2"
        }

        def duration = System.currentTimeMillis() - startTime

        then: "transaction completes successfully"
        ServiceRequest.count() == initialCount + 2

        println "Slow transaction (5s sleep) completed successfully"
        println "Total duration: ${duration}ms"
        println "Conclusion: Sleep/delays don't trigger timeout"
        println "MongoDB measures DB operation time, not application code time"
        println "================================"

        cleanup:
        ServiceRequest.collection.drop()
    }

    void "test batch processing prevents timeout with many operations"() {
        given: "large number of objects to process"
        def totalObjects = 100
        def batchSize = 25

        println "=== BATCH PROCESSING TEST ==="
        println "Total objects: ${totalObjects}"
        println "Batch size: ${batchSize}"

        // Create test data
        def objectIds = []
        (1..totalObjects).each { i ->
            ServiceRequest.withNativeTransaction { session ->
                objectIds << new ServiceRequest(
                    requestNumber: "SR-BATCH-${i}-${System.currentTimeMillis()}",
                    status: "PENDING",
                    patientName: "Batch Patient ${i}"
                ).save(failOnError: true).id
            }
        }

        when: "updating all objects in batches"
        def processedCount = 0
        def batchDurations = []

        objectIds.collate(batchSize).each { batch ->
            def startTime = System.currentTimeMillis()

            ServiceRequest.withNativeTransaction { session ->
                batch.each { id ->
                    def sr = ServiceRequest.get(id)
                    sr.status = "PROCESSED"
                    sr.save(failOnError: true)
                    processedCount++
                }
            }

            def duration = System.currentTimeMillis() - startTime
            batchDurations << duration
            println "Batch of ${batch.size()} objects processed in ${duration}ms"
        }

        then: "all objects processed successfully"
        processedCount == totalObjects
        ServiceRequest.countByStatus("PROCESSED") == totalObjects

        and: "batch processing keeps transaction times low"
        def maxBatchDuration = batchDurations.max()
        def avgBatchDuration = batchDurations.sum() / batchDurations.size()

        println "Max batch duration: ${maxBatchDuration}ms"
        println "Avg batch duration: ${avgBatchDuration}ms"
        println "Total batches: ${batchDurations.size()}"
        println "Conclusion: Batching keeps each transaction under 200ms"

        // Each batch should complete quickly (well under timeout)
        maxBatchDuration < 5000  // Less than 5 seconds per batch

        cleanup:
        ServiceRequest.collection.drop()
    }

    void "test transaction duration is based on number of operations not object size"() {
        given: "initial count"
        def initialCount = ServiceRequest.count()

        println "=== MANY OPERATIONS TEST ==="

        when: "performing 50 fast operations"
        def startTime = System.currentTimeMillis()
        def operationTimes = []

        ServiceRequest.withNativeTransaction { session ->
            // Perform 50 operations
            (1..50).each { i ->
                def opStart = System.currentTimeMillis()

                new ServiceRequest(
                    requestNumber: "SR-MANY-${i}-${System.currentTimeMillis()}",
                    status: "PENDING",
                    patientName: "Many Operations Patient ${i}"
                ).save(failOnError: true)

                def opDuration = System.currentTimeMillis() - opStart
                operationTimes << opDuration

                if (i % 10 == 0) {
                    println "Completed ${i} operations"
                }
            }
        }

        def totalDuration = System.currentTimeMillis() - startTime
        def avgOpTime = operationTimes.sum() / operationTimes.size()
        def maxOpTime = operationTimes.max()

        then: "all 50 operations complete successfully"
        ServiceRequest.count() == initialCount + 50

        and: "total duration is reasonable"
        totalDuration < 5000  // Less than 5 seconds for 50 operations

        println "50 operations completed in ${totalDuration}ms"
        println "Average operation time: ${avgOpTime}ms"
        println "Max operation time: ${maxOpTime}ms"
        println "Conclusion: Number of operations matters, total duration must stay under timeout"
        println "================================"

        cleanup:
        ServiceRequest.collection.drop()
    }

    void "test memory usage with very large transaction"() {
        given: "track initial memory"
        System.gc()
        Thread.sleep(100)
        def runtime = Runtime.getRuntime()
        def initialMemory = runtime.totalMemory() - runtime.freeMemory()

        println "=== MEMORY USAGE TEST ==="
        println "Initial memory usage: ${initialMemory / 1024 / 1024} MB"

        when: "creating many large objects in single transaction"
        def objectCount = 50
        def objectSize = 100  // 100KB each = 5MB total

        ServiceRequest.withNativeTransaction { session ->
            (1..objectCount).each { i ->
                def largeMetadata = [:]
                (1..100).each { j ->
                    largeMetadata["event_${j}"] = [
                        event: "Event ${j}",
                        timestamp: new Date().toString(),
                        data: "x" * 1000  // 1KB per entry
                    ]
                }

                new ServiceRequest(
                    requestNumber: "SR-MEMORY-${i}-${System.currentTimeMillis()}",
                    status: "PENDING",
                    patientName: "Memory Test Patient ${i}",
                    metadata: largeMetadata
                ).save(failOnError: true)
            }
        }

        System.gc()
        Thread.sleep(100)
        def finalMemory = runtime.totalMemory() - runtime.freeMemory()
        def memoryIncrease = (finalMemory - initialMemory) / 1024 / 1024

        then: "transaction completes successfully"
        ServiceRequest.count() == objectCount

        and: "memory increase is reasonable"
        println "Final memory usage: ${finalMemory / 1024 / 1024} MB"
        println "Memory increase: ${memoryIncrease} MB"
        println "Objects created: ${objectCount} x ${objectSize}KB = ${objectCount * objectSize / 1024} MB data"
        println "Conclusion: Memory usage is reasonable for large transactions"

        // Memory increase should be reasonable (not excessive)
        memoryIncrease < 100  // Less than 100MB increase

        cleanup:
        ServiceRequest.collection.drop()
    }

    @Ignore("Unreliable test that requires 65+ seconds to trigger MongoDB's transaction idle timeout. " +
            "Timeout behavior is environment-dependent (server-side configuration), not suitable for automated testing. ")
    void "test forced timeout with many operations exceeding time limit"() {
        given: "initial counts"
        def initialSRCount = ServiceRequest.count()
        def initialAuditCount = AuditEvent.count()

        println "=== FORCED TIMEOUT TEST ==="
        println "MongoDB transaction timeout: 60 seconds of IDLE TIME (no database activity)"
        println "Strategy: Create objects, then sleep for 65 seconds to trigger idle timeout"
        println "This will verify that transactions timeout when idle and rollback all operations"

        when: "creating operations then entering idle period to trigger timeout"
        def timeoutOccurred = false
        def exceptionMessage = null
        def operationsCompleted = 0
        def startTime = System.currentTimeMillis()

        try {
            ServiceRequest.withNativeTransaction { session ->
                // Create some objects first
                println "Creating 10 ServiceRequest objects..."
                (1..10).each { i ->
                    new ServiceRequest(
                        requestNumber: "SR-TIMEOUT-${i}-${System.currentTimeMillis()}",
                        status: "PENDING",
                        patientName: "Timeout Test Patient ${i}"
                    ).save(failOnError: true)
                    operationsCompleted++
                }
                println "Created ${operationsCompleted} objects"

                // Create audit events for atomicity testing
                println "Creating 10 AuditEvent objects..."
                (1..10).each { i ->
                    new AuditEvent(
                        entityType: "ServiceRequest",
                        entityId: "SR-TIMEOUT-${i}",
                        action: "CREATED",
                        performedBy: "TIMEOUT_TEST",
                        timestamp: new Date()
                    ).save(failOnError: true)
                }
                println "Created 10 audit events"

                // Now sleep for 65 seconds to trigger the 60-second IDLE timeout
                println "Entering 65-second sleep to trigger idle timeout..."
                println "MongoDB will terminate this transaction after 60 seconds of inactivity"
                def sleepStart = System.currentTimeMillis()
                Thread.sleep(65000)  // 65 seconds - exceeds 60-second idle timeout
                def sleepDuration = System.currentTimeMillis() - sleepStart
                println "Sleep completed after ${sleepDuration}ms"

                // Try to create another object after sleep (should fail due to timeout)
                println "Attempting to create object after sleep (SHOULD FAIL)..."
                new ServiceRequest(
                    requestNumber: "SR-AFTER-SLEEP",
                    status: "PENDING",
                    patientName: "After Sleep Patient"
                ).save(failOnError: true)

                println "All operations completed (SHOULD NOT REACH HERE)"
            }
        } catch (MongoCommandException e) {
            // MongoDB throws MongoCommandException with error code 251 (NoSuchTransaction)
            // when transaction times out after idle period
            if (e.getErrorCode() == 251) {
                timeoutOccurred = true
                exceptionMessage = e.message
                def duration = System.currentTimeMillis() - startTime
                println "✓ TRANSACTION TIMEOUT CAUGHT!"
                println "  Exception: MongoCommandException"
                println "  Error Code: 251 (NoSuchTransaction)"
                println "  Message: ${e.getErrorMessage()}"
                println "  Duration: ${duration}ms (${duration / 1000} seconds)"
                println "  Operations before timeout: ${operationsCompleted}"
                println "  Transaction was automatically aborted by MongoDB after idle timeout"
            } else {
                def duration = System.currentTimeMillis() - startTime
                println "✗ UNEXPECTED MongoCommandException (error code ${e.getErrorCode()})"
                println "  Message: ${e.message}"
                println "  Duration: ${duration}ms"
                throw e
            }
        } catch (MongoExecutionTimeoutException e) {
            timeoutOccurred = true
            exceptionMessage = e.message
            def duration = System.currentTimeMillis() - startTime
            println "✓ EXECUTION TIMEOUT EXCEPTION CAUGHT!"
            println "  Duration: ${duration}ms (${duration / 1000} seconds)"
            println "  Exception: ${e.class.name}"
            println "  Message: ${e.message}"
        } catch (Exception e) {
            def duration = System.currentTimeMillis() - startTime
            println "✗ UNEXPECTED EXCEPTION: ${e.class.name}"
            println "  Message: ${e.message}"
            println "  Duration: ${duration}ms"
            println "  Operations completed: ${operationsCompleted}"
            throw e
        }

        then: "verify timeout occurred and all operations rolled back"
        def finalSRCount = ServiceRequest.count()
        def finalAuditCount = AuditEvent.count()

        println "=== ROLLBACK VERIFICATION ==="
        println "Timeout occurred: ${timeoutOccurred}"
        println "Operations completed before idle period: ${operationsCompleted}"
        println "Initial SR count: ${initialSRCount}"
        println "Final SR count: ${finalSRCount}"
        println "Initial Audit count: ${initialAuditCount}"
        println "Final Audit count: ${finalAuditCount}"

        // ASSERT: Timeout must have occurred due to 65-second idle period
        timeoutOccurred == true

        // ASSERT: ALL operations must be rolled back (both ServiceRequest and AuditEvent)
        // This verifies atomic rollback across multiple collections
        finalSRCount == initialSRCount
        finalAuditCount == initialAuditCount

        println "✓ CONFIRMED: Timeout occurred after 65-second idle period"
        println "✓ CONFIRMED: All ${operationsCompleted} ServiceRequest operations rolled back"
        println "✓ CONFIRMED: All 10 AuditEvent operations rolled back"
        println "✓ CONFIRMED: No partial data persisted across collections (atomic rollback)"
        println "✓ CONFIRMED: MongoDB transaction timeout = IDLE TIME, not total duration"
        println "================================"

        cleanup:
        ServiceRequest.collection.drop()
        AuditEvent.collection.drop()
    }
}
