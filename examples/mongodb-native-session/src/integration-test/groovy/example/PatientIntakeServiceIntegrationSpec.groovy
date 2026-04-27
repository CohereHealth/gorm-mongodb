package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

import java.time.LocalDateTime

/**
 * Tests PatientIntakeService (legacy @Transactional) calling AppointmentService (native).
 * The native service opens its own MongoDB session and commits independently,
 * so native writes survive even if the legacy caller fails.
 */
@Integration
@NativeRollback
class PatientIntakeServiceIntegrationSpec extends Specification {

    PatientIntakeService patientIntakeService

    void "test intake registers patient and schedules appointment"() {
        given:
        def initialPatientCount = Patient.count()
        def initialApptCount = Appointment.count()

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

        and: "verify counts increased by exactly 1"
        Patient.count() == initialPatientCount + 1
        Appointment.count() == initialApptCount + 1
    }

    void "test intake failure does not roll back native appointment"() {
        given:
        def initialApptCount = Appointment.count()

        when:
        patientIntakeService.registerAndScheduleWithFailure(
            'Frank', 'Garcia', 28, 'Dr. Tanaka', 'ENT', LocalDateTime.now().plusDays(3)
        )

        then:
        thrown(RuntimeException)

        and: "native appointment was committed independently and persists"
        Appointment.count() == initialApptCount + 1
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
