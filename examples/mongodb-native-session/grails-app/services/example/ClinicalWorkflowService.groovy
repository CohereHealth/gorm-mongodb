package example

import groovy.util.logging.Slf4j

import java.time.LocalDateTime

/**
 * Native service that delegates to another native service (AppointmentService).
 * Tests nested native-to-native service interaction where inner calls
 * join the outer native session.
 */
@Slf4j
class ClinicalWorkflowService {

    AppointmentService appointmentService

    /**
     * Schedules an appointment and writes a prescription in the same native transaction.
     * Both AppointmentService calls join the outer session.
     */
    def scheduleVisitWithPrescription(String patientName, String providerName,
                                      String department, LocalDateTime scheduledDate,
                                      String medicationName, String dosage, Integer refills) {
        return Appointment.withNativeTransaction {
            def appt = appointmentService.scheduleAppointment(patientName, providerName, department, scheduledDate)
            def rx = appointmentService.writePrescription(patientName, providerName, medicationName, dosage, refills)
            log.info("Scheduled visit ${appt.id} with prescription ${rx.id}")
            [appointment: appt, prescription: rx]
        }
    }

    /**
     * Schedules an appointment, checks in, then completes - all nested native calls.
     */
    def fullVisitWorkflow(String patientName, String providerName, String department, LocalDateTime scheduledDate) {
        return Appointment.withNativeTransaction {
            def appt = appointmentService.scheduleAppointment(patientName, providerName, department, scheduledDate)
            appointmentService.checkIn(appt.id)
            appointmentService.completeAppointment(appt.id)
            Appointment.get(appt.id)
        }
    }

    /**
     * Schedules appointment via nested native call then fails -
     * verifying the nested service's writes roll back with the outer transaction.
     */
    def scheduleAndFail(String patientName, String providerName, String department, LocalDateTime scheduledDate) {
        return Appointment.withNativeTransaction {
            appointmentService.scheduleAppointment(patientName, providerName, department, scheduledDate)
            throw new RuntimeException("Workflow failure after scheduling")
        }
    }

    /**
     * Schedules multiple appointments via nested native calls.
     * All commit or roll back atomically.
     */
    def scheduleBatch(List<Map> appointmentSpecs) {
        return Appointment.withNativeTransaction {
            appointmentSpecs.collect { spec ->
                appointmentService.scheduleAppointment(
                    spec.patientName, spec.providerName,
                    spec.department, spec.scheduledDate
                )
            }
        }
    }

    /**
     * Schedules multiple appointments but fails after the second -
     * all should roll back.
     */
    def scheduleBatchWithFailure(List<Map> appointmentSpecs) {
        return Appointment.withNativeTransaction {
            appointmentSpecs.eachWithIndex { spec, idx ->
                appointmentService.scheduleAppointment(
                    spec.patientName, spec.providerName,
                    spec.department, spec.scheduledDate
                )
                if (idx == 1) {
                    throw new RuntimeException("Failure after second appointment")
                }
            }
        }
    }
}
