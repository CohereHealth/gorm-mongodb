package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

import java.time.LocalDateTime

/**
 * Tests ClinicalWorkflowService (native) delegating to AppointmentService (native).
 * Inner calls join the outer native session — all commit or roll back atomically.
 */
@Integration
@NativeRollback
class ClinicalWorkflowServiceIntegrationSpec extends Specification {

    ClinicalWorkflowService clinicalWorkflowService

    void "test schedule visit with prescription in nested native calls"() {
        when:
        def result = clinicalWorkflowService.scheduleVisitWithPrescription(
            'John Doe', 'Dr. Smith', 'Cardiology', LocalDateTime.now().plusDays(7),
            'Lisinopril', '10mg daily', 3
        )

        then:
        result.appointment != null
        result.appointment.status == 'SCHEDULED'
        result.prescription != null
        result.prescription.status == 'ACTIVE'
        result.prescription.medicationName == 'Lisinopril'
        Appointment.count() == 1
        Prescription.count() == 1
    }

    void "test full visit workflow through nested native calls"() {
        when:
        def appt = clinicalWorkflowService.fullVisitWorkflow(
            'Jane Doe', 'Dr. Adams', 'Neurology', LocalDateTime.now()
        )

        then:
        appt.status == 'COMPLETED'
        Appointment.get(appt.id).status == 'COMPLETED'
    }

    void "test nested native failure rolls back all writes"() {
        given:
        def initialCount = Appointment.count()

        when:
        clinicalWorkflowService.scheduleAndFail(
            'Rollback Patient', 'Dr. Brown', 'Oncology', LocalDateTime.now().plusDays(3)
        )

        then:
        thrown(RuntimeException)
        Appointment.count() == initialCount
    }

    void "test batch scheduling through nested native calls"() {
        given:
        def specs = (1..5).collect { i ->
            [patientName: "Patient $i", providerName: 'Dr. Lee',
             department: 'General', scheduledDate: LocalDateTime.now().plusDays(i)]
        }

        when:
        def appointments = clinicalWorkflowService.scheduleBatch(specs)

        then:
        appointments.size() == 5
        Appointment.count() == 5
    }

    void "test batch failure rolls back all nested native writes"() {
        given:
        def initialCount = Appointment.count()
        def specs = (1..4).collect { i ->
            [patientName: "Batch Patient $i", providerName: 'Dr. Kim',
             department: 'Pediatrics', scheduledDate: LocalDateTime.now().plusDays(i)]
        }

        when:
        clinicalWorkflowService.scheduleBatchWithFailure(specs)

        then:
        thrown(RuntimeException)
        Appointment.count() == initialCount
    }

    void "test optimistic locking across nested native service calls"() {
        when:
        def appt = clinicalWorkflowService.fullVisitWorkflow(
            'Version Patient', 'Dr. Park', 'Radiology', LocalDateTime.now()
        )

        then: "version incremented for each status change: SCHEDULED -> CHECKED_IN -> COMPLETED"
        appt.version >= 2
    }
}
