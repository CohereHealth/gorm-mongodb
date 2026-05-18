package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j

/**
 * Legacy service using Spring-managed @Transactional.
 * NOT migrated to native transactions — uses flush-based persistence.
 */
@Slf4j
@Transactional
class LabResultService {

    def orderLab(String patientName, String testName, String orderedBy) {
        def lab = new LabResult(
            patientName: patientName, testName: testName,
            orderedBy: orderedBy, status: 'ORDERED'
        )
        lab.save(flush: true, failOnError: true)
        log.info("Ordered lab ${lab.id}: ${testName} for ${patientName}")
        return lab
    }

    def recordResult(Serializable labId, String result) {
        def lab = LabResult.get(labId)
        if (!lab) throw new IllegalArgumentException("Lab not found: ${labId}")
        lab.result = result
        lab.status = 'COMPLETED'
        lab.save(flush: true, failOnError: true)
    }

    def cancelLab(Serializable labId) {
        def lab = LabResult.get(labId)
        if (!lab) throw new IllegalArgumentException("Lab not found: ${labId}")
        lab.status = 'CANCELLED'
        lab.save(flush: true, failOnError: true)
    }

    def orderLabWithFailure(String patientName, String testName, String orderedBy) {
        def lab = new LabResult(
            patientName: patientName, testName: testName,
            orderedBy: orderedBy, status: 'ORDERED'
        )
        lab.save(flush: true, failOnError: true)
        throw new RuntimeException("Simulated legacy failure")
    }
}
