package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j

@Slf4j
@Transactional
class PatientService {

    def createPatient(String firstName, String lastName, Integer age) {
        def patient = new Patient(firstName: firstName, lastName: lastName, age: age)
        patient.save(flush: true, failOnError: true)
        return patient
    }

    def updatePatient(Serializable patientId, Map updates) {
        def patient = Patient.get(patientId)
        if (!patient) {
            throw new IllegalArgumentException("Patient not found: ${patientId}")
        }
        updates.each { key, value ->
            patient[key] = value
        }
        patient.save(failOnError: true)
        return patient
    }

    def deletePatient(Serializable patientId) {
        def patient = Patient.get(patientId)
        if (!patient) {
            throw new IllegalArgumentException("Patient not found: ${patientId}")
        }
        patient.delete()
        return true
    }

    def createMultiplePatients(List<Map> patientData, boolean shouldFail = false) {
        def created = []
        patientData.each { data ->
            def patient = new Patient(data)
            patient.save(failOnError: true)
            created << patient
        }
        if (shouldFail) {
            throw new RuntimeException("Intentional failure")
        }
        return created
    }
}
