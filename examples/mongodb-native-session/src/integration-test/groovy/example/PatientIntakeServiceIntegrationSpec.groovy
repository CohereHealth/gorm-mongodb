package example

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import spock.lang.Specification

import java.time.LocalDateTime

/**
 * Tests PatientIntakeService (legacy @Transactional) calling AppointmentService (native).
 * The native service opens its own MongoDB session and commits independently,
 * so native writes survive even if the legacy caller fails.
 */
@Integration
@Rollback
class PatientIntakeServiceIntegrationSpec extends Specification {

    PatientIntakeService patientIntakeService

    void cleanup() {
        Patient.collection.drop()
        Appointment.withNewNativeTransaction { Appointment.collection.drop() }
        LabResult.withNewNativeTransaction { LabResult.collection.drop() }
    }

    void "test intake registers patient and schedules appointment"() {
        when:
        def result = patientIntakeService.registerAndSchedule(
            'Eve', 'Martinez', 35, 'Dr. Shah', 'Dermatology', LocalDateTime.now().plusDays(5)
        )

        then:
        result.patient != null
        result.patient.firstName == 'Eve'
        result.appointment != null
        result.appointment.status == 'SCHEDULED'
        result.appointment.patientName == 'Eve Martinez'
        Patient.count() == 1

        and:
        Appointment.withNewNativeTransaction {
            Appointment.count() == 1
        }
    }

    void "test intake failure does not roll back native appointment"() {
        given:
        def initialApptCount = Appointment.withNewNativeTransaction { Appointment.count() }

        when:
        patientIntakeService.registerAndScheduleWithFailure(
            'Frank', 'Garcia', 28, 'Dr. Tanaka', 'ENT', LocalDateTime.now().plusDays(3)
        )

        then:
        thrown(RuntimeException)

        and: "native appointment was committed independently and persists"
        Appointment.withNewNativeTransaction {
            Appointment.count() == initialApptCount + 1
        }
    }

    void "test full intake with mixed legacy and native services"() {
        when:
        def result = patientIntakeService.fullIntake(
            'Grace', 'Lee', 42, 'Dr. Rivera', 'Internal Medicine',
            LocalDateTime.now().plusDays(7), 'Complete Blood Count'
        )

        then:
        result.patient.firstName == 'Grace'
        result.labResult.testName == 'Complete Blood Count'
        result.appointment.department == 'Internal Medicine'
    }
}
