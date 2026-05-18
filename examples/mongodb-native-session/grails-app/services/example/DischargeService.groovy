package example

import groovy.util.logging.Slf4j

/**
 * Native service that calls legacy LabResultService (Spring @Transactional).
 *
 * Demonstrates two distinct behaviors:
 * 1. Legacy called INSIDE native transaction — legacy writes join the native
 *    session and commit/roll back together with native writes.
 * 2. Legacy called OUTSIDE native transaction — legacy writes are independent
 *    and persist even if the native transaction rolls back.
 */
@Slf4j
class DischargeService {

    LabResultService labResultService
    AppointmentService appointmentService

    // ---------------------------------------------------------------
    // Case 1: Legacy called INSIDE native transaction (writes join)
    // ---------------------------------------------------------------

    /**
     * Completes an appointment (native) and orders a follow-up lab (legacy)
     * within a native transaction scope. Both writes are part of the same
     * native transaction.
     */
    def dischargeWithLabOrder(Serializable appointmentId, String patientName, String testName, String orderedBy) {
        return Appointment.withNativeTransaction {
            def appt = appointmentService.completeAppointment(appointmentId)
            def lab = labResultService.orderLab(patientName, testName, orderedBy)
            log.info("Discharged appointment ${appt.id}, ordered lab ${lab.id}")
            [appointment: appt, labResult: lab]
        }
    }

    /**
     * Legacy called inside native transaction, then fails.
     * Both native and legacy writes roll back together.
     */
    def dischargeWithLabOrderAndFail(Serializable appointmentId, String patientName, String testName, String orderedBy) {
        return Appointment.withNativeTransaction {
            appointmentService.completeAppointment(appointmentId)
            labResultService.orderLab(patientName, testName, orderedBy)
            throw new RuntimeException("Discharge failure after lab order")
        }
    }

    /**
     * Legacy service throws inside native transaction.
     * The exception propagates and rolls back the entire native transaction.
     */
    def dischargeWithLegacyFailure(Serializable appointmentId, String patientName, String testName, String orderedBy) {
        return Appointment.withNativeTransaction {
            appointmentService.completeAppointment(appointmentId)
            labResultService.orderLabWithFailure(patientName, testName, orderedBy)
        }
    }

    // ---------------------------------------------------------------
    // Case 2: Legacy called OUTSIDE native transaction (independent)
    // ---------------------------------------------------------------

    /**
     * Completes appointment in native transaction, then orders lab outside it.
     * The two writes are independent — each commits on its own.
     */
    def dischargeWithIndependentLabOrder(Serializable appointmentId, String patientName, String testName, String orderedBy) {
        def appt = Appointment.withNativeTransaction {
            appointmentService.completeAppointment(appointmentId)
        }
        def lab = labResultService.orderLab(patientName, testName, orderedBy)
        log.info("Discharged appointment ${appt.id}, ordered independent lab ${lab.id}")
        [appointment: appt, labResult: lab]
    }

    /**
     * Native transaction completes appointment, then legacy lab is ordered
     * outside it, then we throw. The native write already committed;
     * the legacy write already flushed. Both persist.
     */
    def dischargeWithIndependentLabOrderAndFail(Serializable appointmentId, String patientName, String testName, String orderedBy) {
        Appointment.withNativeTransaction {
            appointmentService.completeAppointment(appointmentId)
        }
        labResultService.orderLab(patientName, testName, orderedBy)
        throw new RuntimeException("Failure after both committed independently")
    }

    /**
     * Native transaction fails and rolls back, then legacy lab is ordered
     * outside it. Native write is rolled back; legacy write persists.
     */
    def dischargeWithNativeFailureThenIndependentLab(Serializable appointmentId, String patientName, String testName, String orderedBy) {
        try {
            Appointment.withNativeTransaction {
                appointmentService.completeAppointment(appointmentId)
                throw new RuntimeException("Native transaction failure")
            }
        } catch (RuntimeException ignored) {
            // native rolled back, proceed with legacy
        }
        def lab = labResultService.orderLab(patientName, testName, orderedBy)
        [labResult: lab]
    }
}
