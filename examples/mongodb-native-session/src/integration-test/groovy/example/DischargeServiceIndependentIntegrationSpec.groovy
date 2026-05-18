package example

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import spock.lang.Specification

import java.time.LocalDateTime

/**
 * Tests DischargeService rollback and independent transaction behavior.
 *
 * Uses @Rollback (Spring-managed session) with withNewNativeTransaction for
 * setup and assertions so that each operation runs in its own committed
 * transaction — allowing us to verify post-rollback state.
 *
 * Covers:
 * - Legacy inside native: rollback undoes both native and legacy writes
 * - Legacy outside native: writes are independent, each commits on its own
 */
@Integration
@Rollback
class DischargeServiceIndependentIntegrationSpec extends Specification {

    DischargeService dischargeService

    void cleanup() {
        Appointment.withNewNativeTransaction { Appointment.collection.drop() }
        LabResult.withNewNativeTransaction { LabResult.collection.drop() }
    }

    // ---------------------------------------------------------------
    // Legacy called INSIDE native transaction — rollback behavior
    // ---------------------------------------------------------------

    void "test legacy inside native - both writes roll back together on failure"() {
        given:
        def appt = Appointment.withNewNativeTransaction {
            new Appointment(
                patientName: 'Bob Wilson', providerName: 'Dr. Patel',
                department: 'Cardiology', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
            ).save(failOnError: true)
        }
        def initialApptCount = Appointment.withNewNativeTransaction { Appointment.count() }
        def initialLabCount = LabResult.withNewNativeTransaction { LabResult.count() }

        when:
        dischargeService.dischargeWithLabOrderAndFail(
            appt.id, 'Bob Wilson', 'ECG', 'Dr. Patel'
        )

        then:
        thrown(RuntimeException)

        and: "appointment status rolled back"
        Appointment.withNewNativeTransaction {
            Appointment.get(appt.id).status == 'CHECKED_IN'
        }

        and: "no new records — both writes rolled back together"
        Appointment.withNewNativeTransaction { Appointment.count() } == initialApptCount
        LabResult.withNewNativeTransaction { LabResult.count() } == initialLabCount
    }

    void "test legacy inside native - legacy exception rolls back entire native transaction"() {
        given:
        def appt = Appointment.withNewNativeTransaction {
            new Appointment(
                patientName: 'Carol Davis', providerName: 'Dr. Nguyen',
                department: 'Pulmonology', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
            ).save(failOnError: true)
        }
        def initialApptCount = Appointment.withNewNativeTransaction { Appointment.count() }
        def initialLabCount = LabResult.withNewNativeTransaction { LabResult.count() }

        when:
        dischargeService.dischargeWithLegacyFailure(
            appt.id, 'Carol Davis', 'Chest X-Ray', 'Dr. Nguyen'
        )

        then:
        thrown(RuntimeException)

        and: "appointment status unchanged — rolled back to original"
        Appointment.withNewNativeTransaction {
            Appointment.get(appt.id).status
        } == old(Appointment.withNewNativeTransaction { Appointment.get(appt.id).status })

        and: "no new lab records — legacy exception rolled back everything"
        LabResult.withNewNativeTransaction { LabResult.count() } == initialLabCount
    }

    // ---------------------------------------------------------------
    // Legacy called OUTSIDE native transaction — independent behavior
    // ---------------------------------------------------------------

    void "test legacy outside native - both writes commit independently"() {
        given:
        def appt = Appointment.withNewNativeTransaction {
            new Appointment(
                patientName: 'Dan Evans', providerName: 'Dr. Kim',
                department: 'Neurology', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
            ).save(failOnError: true)
        }

        when:
        def result = dischargeService.dischargeWithIndependentLabOrder(
            appt.id, 'Dan Evans', 'MRI Brain', 'Dr. Kim'
        )

        then:
        result.appointment.status == 'COMPLETED'
        result.labResult.testName == 'MRI Brain'

        and:
        Appointment.withNewNativeTransaction {
            Appointment.get(appt.id).status == 'COMPLETED'
        }
        LabResult.withNewNativeTransaction {
            LabResult.findByPatientName('Dan Evans') != null
        }
    }

    void "test legacy outside native - both persist even when caller fails after"() {
        given:
        def appt = Appointment.withNewNativeTransaction {
            new Appointment(
                patientName: 'Fay Harris', providerName: 'Dr. Lopez',
                department: 'Radiology', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
            ).save(failOnError: true)
        }

        when:
        dischargeService.dischargeWithIndependentLabOrderAndFail(
            appt.id, 'Fay Harris', 'CT Scan', 'Dr. Lopez'
        )

        then:
        thrown(RuntimeException)

        and: "native appointment committed before the failure"
        Appointment.withNewNativeTransaction {
            Appointment.get(appt.id).status == 'COMPLETED'
        }

        and: "legacy lab also committed before the failure"
        LabResult.withNewNativeTransaction {
            LabResult.findByPatientName('Fay Harris') != null
        }
    }

    void "test legacy outside native - native rolls back but legacy persists"() {
        given:
        def appt = Appointment.withNewNativeTransaction {
            new Appointment(
                patientName: 'Gina Ito', providerName: 'Dr. Reyes',
                department: 'Oncology', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
            ).save(failOnError: true)
        }

        when:
        def result = dischargeService.dischargeWithNativeFailureThenIndependentLab(
            appt.id, 'Gina Ito', 'Biopsy Panel', 'Dr. Reyes'
        )

        then: "native appointment status rolled back"
        Appointment.withNewNativeTransaction {
            Appointment.get(appt.id).status == 'CHECKED_IN'
        }

        and: "legacy lab persists — called after native rolled back"
        result.labResult != null
        LabResult.withNewNativeTransaction {
            LabResult.findByPatientName('Gina Ito')?.testName == 'Biopsy Panel'
        }
    }
}
