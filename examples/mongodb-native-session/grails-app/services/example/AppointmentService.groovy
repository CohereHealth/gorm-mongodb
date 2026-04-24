package example

import groovy.util.logging.Slf4j

import java.time.LocalDateTime

/**
 * Native transaction service for appointment management.
 * All methods use withNativeTransaction for MongoDB native sessions.
 */
@Slf4j
class AppointmentService {

    def scheduleAppointment(String patientName, String providerName, String department, LocalDateTime scheduledDate) {
        return Appointment.withNativeTransaction {
            def appt = new Appointment(
                patientName: patientName, providerName: providerName,
                department: department, status: 'SCHEDULED', scheduledDate: scheduledDate
            ).save(failOnError: true)
            log.info("Scheduled appointment ${appt.id} for ${patientName}")
            return appt
        }
    }

    def checkIn(Serializable appointmentId) {
        return Appointment.withNativeTransaction {
            def appt = Appointment.get(appointmentId)
            if (!appt) throw new IllegalArgumentException("Appointment not found: ${appointmentId}")
            appt.status = 'CHECKED_IN'
            appt.save(failOnError: true)
        }
    }

    def completeAppointment(Serializable appointmentId) {
        return Appointment.withNativeTransaction {
            def appt = Appointment.get(appointmentId)
            if (!appt) throw new IllegalArgumentException("Appointment not found: ${appointmentId}")
            appt.status = 'COMPLETED'
            appt.save(failOnError: true)
        }
    }

    def cancelAppointment(Serializable appointmentId) {
        return Appointment.withNativeTransaction {
            def appt = Appointment.get(appointmentId)
            if (!appt) throw new IllegalArgumentException("Appointment not found: ${appointmentId}")
            appt.status = 'CANCELLED'
            appt.save(failOnError: true)
        }
    }

    def writePrescription(String patientName, String providerName, String medicationName, String dosage, Integer refills) {
        return Prescription.withNativeTransaction {
            new Prescription(
                patientName: patientName, providerName: providerName,
                medicationName: medicationName, dosage: dosage,
                refills: refills, status: 'ACTIVE'
            ).save(failOnError: true)
        }
    }
}
