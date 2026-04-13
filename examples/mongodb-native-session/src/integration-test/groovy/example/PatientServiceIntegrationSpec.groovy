package example

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import org.bson.types.ObjectId
import spock.lang.Specification

/**
 * Integration tests for standard Spring-managed transactions (non-native).
 * {@code @Rollback} is required to bind a GORM session to the thread for the
 * duration of each test. Manual cleanup drops the collection after each test
 * since Spring's rollback does not revert writes in MongoDB.
 */
@Integration
@Rollback
class PatientServiceIntegrationSpec extends Specification {

    PatientService patientService

    void cleanup() {
        Patient.collection.drop()
    }

    void "test create patient"() {
        when:
        def patient = patientService.createPatient("John", "Doe", 30)

        then:
        patient != null
        patient.id instanceof ObjectId
        patient.firstName == "John"
        patient.lastName == "Doe"
        patient.age == 30
        Patient.count() == 1
    }

    void "test update patient"() {
        given:
        def patient = new Patient(firstName: "Jane", lastName: "Smith", age: 25).save(flush: true, failOnError: true)

        when:
        def updated = patientService.updatePatient(patient.id, [age: 26, firstName: "Janet"])

        then:
        updated.age == 26
        updated.firstName == "Janet"
        updated.lastName == "Smith"

        and:
        def reloaded = Patient.get(patient.id)
        reloaded.age == 26
        reloaded.firstName == "Janet"
    }

    void "test delete patient"() {
        given:
        def patient = new Patient(firstName: "Bob", lastName: "Johnson", age: 35).save(flush: true, failOnError: true)

        when:
        def result = patientService.deletePatient(patient.id)

        then:
        result == true
        Patient.get(patient.id) == null
        Patient.count() == old(Patient.count()) - 1
    }

    void "test create multiple patients"() {
        given:
        def data = [
            [firstName: "Alice", lastName: "Brown", age: 28],
            [firstName: "Charlie", lastName: "Davis", age: 32],
            [firstName: "Diana", lastName: "Wilson", age: 29]
        ]

        when:
        def created = patientService.createMultiplePatients(data)

        then:
        created.size() == 3
        Patient.count() == 3
        created.every { it.id instanceof ObjectId }
    }

    void "test rollback on failure"() {
        given:
        def data = [
            [firstName: "Eve", lastName: "Miller", age: 27],
            [firstName: "Frank", lastName: "Garcia", age: 31]
        ]

        when:
        patientService.createMultiplePatients(data, true)

        then:
        thrown(RuntimeException)
        Patient.count() == old(Patient.count())
    }

    void "test optimistic locking"() {
        given:
        def patient = new Patient(firstName: "Version", lastName: "Test", age: 30).save(flush: true, failOnError: true)

        when:
        patient.age = 31
        patient.save(flush: true, failOnError: true)

        then:
        patient.version == old(patient.version) + 1

        and:
        def reloaded = Patient.get(patient.id)
        reloaded.age == 31
        reloaded.version == old(patient.version) + 1
    }

    void "test find operations"() {
        given:
        new Patient(firstName: "Search", lastName: "One", age: 20).save(flush: true, failOnError: true)
        new Patient(firstName: "Search", lastName: "Two", age: 30).save(flush: true, failOnError: true)
        new Patient(firstName: "Other", lastName: "Three", age: 40).save(flush: true, failOnError: true)

        expect:
        Patient.count() == 3
        Patient.findByFirstName("Search") != null
        Patient.findAllByFirstName("Search").size() == 2
        Patient.findByFirstName("Other").lastName == "Three"
    }
}
