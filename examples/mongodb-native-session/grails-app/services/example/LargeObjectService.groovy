package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j

@Slf4j
@Transactional
class LargeObjectService {

    /**
     * Generates a large metadata object similar to core-platform ServiceRequest
     */
    static Map generateLargeMetadata(int sizeKB = 500) {
        def metadata = [:]

        // Simulate core-platform structure
        metadata.patient = [
            id: UUID.randomUUID().toString(),
            firstName: "Test",
            lastName: "Patient",
            dateOfBirth: "1980-01-01",
            memberId: "MEM" + UUID.randomUUID().toString().substring(0, 8),
            contactInfo: [
                phone: "555-1234",
                email: "test@example.com",
                address: [
                    street: "123 Main St",
                    city: "Anytown",
                    state: "CA",
                    zip: "12345"
                ]
            ]
        ]

        metadata.provider = [
            npi: "1234567890",
            name: "Test Provider",
            specialty: "Cardiology",
            facility: [
                name: "Test Hospital",
                address: "456 Medical Dr"
            ]
        ]

        metadata.service = [
            code: "CPT12345",
            description: "Medical procedure",
            requestedDate: new Date().toString(),
            urgency: "ROUTINE"
        ]

        metadata.clinical = [
            diagnosis: [
                [code: "ICD10-001", description: "Diagnosis 1"],
                [code: "ICD10-002", description: "Diagnosis 2"]
            ],
            procedures: []
        ]

        // Pad to reach desired size
        def currentSize = groovy.json.JsonOutput.toJson(metadata).bytes.length
        def targetBytes = sizeKB * 1024
        if (currentSize < targetBytes) {
            def paddingNeeded = targetBytes - currentSize
            def paddingChunks = (paddingNeeded / 100) as int
            metadata.padding = (1..paddingChunks).collect { "x" * 100 }.join("")
        }

        return metadata
    }

    /**
     * Creates service request with large embedded document
     */
    def createLargeServiceRequest(int sizeKB = 500) {
        def startTime = System.currentTimeMillis()
        log.info("Creating large service request (${sizeKB}KB)")

        def result = ServiceRequest.withNativeTransaction { session ->
            def metadata = generateLargeMetadata(sizeKB)
            def sr = new ServiceRequest(
                requestNumber: "SR-LARGE-" + UUID.randomUUID().toString().substring(0, 8),
                status: "PENDING",
                patientName: "Test Patient",
                metadata: metadata
            )
            sr.save(failOnError: true)

            def snapshot = new CoverageSnapshot(
                serviceRequestNumber: sr.requestNumber,
                snapshotType: "INITIAL",
                coverageData: generateLargeMetadata(sizeKB / 2 as int),  // Half size
                capturedAt: new Date()
            )
            snapshot.save(failOnError: true)

            return [serviceRequest: sr, snapshot: snapshot]
        }

        def elapsedMs = System.currentTimeMillis() - startTime
        log.info("Large object creation took ${elapsedMs}ms")

        return [result: result, latencyMs: elapsedMs]
    }

    /**
     * Updates large service request - tests OLE with large objects
     */
    def updateLargeServiceRequest(String requestNumber, Map updates) {
        def startTime = System.currentTimeMillis()
        log.info("Updating large service request: ${requestNumber}")

        def result = ServiceRequest.withNativeTransaction { session ->
            def sr = ServiceRequest.findByRequestNumber(requestNumber)
            if (!sr) {
                throw new IllegalArgumentException("ServiceRequest not found")
            }

            updates.each { key, value -> sr[key] = value }
            sr.save(failOnError: true)

            return sr
        }

        def elapsedMs = System.currentTimeMillis() - startTime
        log.info("Large object update took ${elapsedMs}ms")

        return [result: result, latencyMs: elapsedMs]
    }

    /**
     * Simulates concurrent updates leading to OLE
     */
    def simulateConcurrentUpdates(String requestNumber, int concurrentThreads = 3) {
        log.info("Simulating ${concurrentThreads} concurrent updates on ${requestNumber}")

        def results = []
        def threads = []

        concurrentThreads.times { threadNum ->
            threads << Thread.start {
                try {
                    Thread.sleep(threadNum * 100)  // Stagger slightly
                    def result = updateLargeServiceRequest(requestNumber, [status: "STATUS-${threadNum}"])
                    synchronized(results) {
                        results << [thread: threadNum, success: true, latency: result.latencyMs]
                    }
                } catch (Exception e) {
                    synchronized(results) {
                        results << [thread: threadNum, success: false, error: e.class.simpleName, message: e.message]
                    }
                }
            }
        }

        threads*.join()
        return results
    }
}
