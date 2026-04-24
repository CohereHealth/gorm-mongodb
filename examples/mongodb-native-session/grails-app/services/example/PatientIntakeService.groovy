package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j

import java.time.LocalDateTime

/**
 * Legacy service (Spring @Transactional) that calls native AppointmentService.
 * Tests what happens when a non-native transaction context calls into
 * a service that uses withNativeTransaction.
 */
@Slf4j
@Transactional
class PatientIntakeService {

    AppointmentService appointmentService
    LabResultService labResultService

    /**
     * Legacy service creates a patient (flush-based) then schedules an
     * appointment via native AppointmentService.
     */
    def registerAndSchedule(String firstName, String lastName, Integer age,
                            String providerName, String department, LocalDateTime scheduledDate) {
        def patient = new Patient(firstName: firstName, lastName: lastName, age: age)
        patient.save(flush: true, failOnError: true)

        def appt = appointmentService.scheduleAppointment(
            "${firstName} ${lastName}", providerName, department, scheduledDate
        )
        log.info("Registered patient ${patient.id}, scheduled appointment ${appt.id}")
        [patient: patient, appointment: appt]
    }

    /**
     * Legacy service creates patient, calls native service, then fails.
     * Patient write (already flushed) persists; native appointment rolls back.
     */
    def registerAndScheduleWithFailure(String firstName, String lastName, Integer age,
                                       String providerName, String department, LocalDateTime scheduledDate) {
        def patient = new Patient(firstName: firstName, lastName: lastName, age: age)
        patient.save(flush: true, failOnError: true)

        appointmentService.scheduleAppointment(
            "${firstName} ${lastName}", providerName, department, scheduledDate
        )
        throw new RuntimeException("Intake failure after scheduling")
    }

    /**
     * Legacy service creates patient, orders lab (legacy), and schedules
     * appointment (native) — mixed transaction types in one call.
     */
    def fullIntake(String firstName, String lastName, Integer age,
                   String providerName, String department, LocalDateTime scheduledDate,
                   String testName) {
        def patient = new Patient(firstName: firstName, lastName: lastName, age: age)
        patient.save(flush: true, failOnError: true)

        def lab = labResultService.orderLab("${firstName} ${lastName}", testName, providerName)
        def appt = appointmentService.scheduleAppointment(
            "${firstName} ${lastName}", providerName, department, scheduledDate
        )
        [patient: patient, labResult: lab, appointment: appt]
    }
}
