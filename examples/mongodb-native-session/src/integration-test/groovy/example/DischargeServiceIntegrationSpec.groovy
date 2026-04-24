package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

import java.time.LocalDateTime

/**
 * Case 1: Legacy service called INSIDE a native transaction.
 *
 * The legacy service's writes go through the native persister because a native
 * session is already active. Both native and legacy writes are part of the
 * same native transaction.
 *
 * Post-rollback state assertions require independent transactions and are
 * covered in {@link DischargeServiceIndependentIntegrationSpec}.
 */
@Integration
@NativeRollback
class DischargeServiceIntegrationSpec extends Specification {

    DischargeService dischargeService

    void "test legacy inside native - both writes commit together"() {
        given:
        def appt = new Appointment(
            patientName: 'Alice Brown', providerName: 'Dr. Chen',
            department: 'Orthopedics', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
        ).save(failOnError: true)

        when:
        def result = dischargeService.dischargeWithLabOrder(
            appt.id, 'Alice Brown', 'X-Ray Left Knee', 'Dr. Chen'
        )

        then:
        result.appointment.status == 'COMPLETED'
        result.labResult != null
        result.labResult.testName == 'X-Ray Left Knee'
        result.labResult.status == 'ORDERED'
    }

    void "test legacy inside native - writes are visible within same transaction"() {
        given:
        def appt = new Appointment(
            patientName: 'Bob Wilson', providerName: 'Dr. Patel',
            department: 'Cardiology', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
        ).save(failOnError: true)

        when:
        def result = dischargeService.dischargeWithLabOrder(
            appt.id, 'Bob Wilson', 'ECG', 'Dr. Patel'
        )

        then: "both writes are immediately visible in the same native session"
        Appointment.get(appt.id).status == 'COMPLETED'
        LabResult.findByPatientName('Bob Wilson')?.testName == 'ECG'
    }

    void "test legacy inside native - native failure throws RuntimeException"() {
        given:
        def appt = new Appointment(
            patientName: 'Carol Davis', providerName: 'Dr. Nguyen',
            department: 'Cardiology', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
        ).save(failOnError: true)

        when:
        dischargeService.dischargeWithLabOrderAndFail(
            appt.id, 'Carol Davis', 'ECG', 'Dr. Nguyen'
        )

        then:
        def e = thrown(RuntimeException)
        e.message == 'Discharge failure after lab order'
        Appointment.count() == old(Appointment.count()) - 1
        LabResult.count() == old(LabResult.count())
    }

    void "test legacy inside native - legacy failure propagates RuntimeException"() {
        given:
        def appt = new Appointment(
            patientName: 'Dan Evans', providerName: 'Dr. Kim',
            department: 'Pulmonology', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
        ).save(failOnError: true)

        when:
        dischargeService.dischargeWithLegacyFailure(
            appt.id, 'Dan Evans', 'Chest X-Ray', 'Dr. Kim'
        )

        then:
        def e = thrown(RuntimeException)
        e.message == 'Simulated legacy failure'
        Appointment.count() == old(Appointment.count()) - 1
        LabResult.count() == old(LabResult.count())
    }
}
