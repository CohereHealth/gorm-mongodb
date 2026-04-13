package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

@Integration
@NativeRollback
class MedicationServiceIntegrationSpec extends Specification {

    MedicationService medicationService

    void "test find facilities by Bson filter via service"() {
        given:
        new Facility(name: 'Bson Hospital', npi: 'NPI080', facilityType: 'HOSPITAL', bedCount: 400).save(failOnError: true)
        new Facility(name: 'Bson Clinic', npi: 'NPI081', facilityType: 'CLINIC', bedCount: 30).save(failOnError: true)

        when:
        def results = medicationService.findFacilitiesByBsonFilter([facilityType: 'HOSPITAL'])

        then:
        results.size() == 1
        results[0].name == 'Bson Hospital'
    }

    void "test count facilities by Bson filter via service"() {
        given:
        new Facility(name: 'Count H1', npi: 'NPI082', facilityType: 'HOSPITAL', bedCount: 100).save(failOnError: true)
        new Facility(name: 'Count H2', npi: 'NPI083', facilityType: 'HOSPITAL', bedCount: 200).save(failOnError: true)
        new Facility(name: 'Count C1', npi: 'NPI084', facilityType: 'CLINIC', bedCount: 10).save(failOnError: true)

        when:
        def count = medicationService.countFacilitiesByBsonFilter([facilityType: 'HOSPITAL'])

        then:
        count == 2
    }

    void "test findOneAndDelete facility via service"() {
        given:
        new Facility(name: 'Delete Me', npi: 'NPI085', facilityType: 'URGENT_CARE', bedCount: 5).save(failOnError: true)
        new Facility(name: 'Keep Me', npi: 'NPI086', facilityType: 'HOSPITAL', bedCount: 100).save(failOnError: true)

        when:
        def deleted = medicationService.findOneAndDeleteFacility([npi: 'NPI085'])

        then:
        deleted != null
        Facility.findByNpi('NPI085') == null
        Facility.findByNpi('NPI086') != null
    }

    void "test search medications via service"() {
        given:
        new Medication(name: 'Aspirin Tablet', category: 'Analgesic', price: 5.99).save(failOnError: true)
        new Medication(name: 'Aspirin Chewable', category: 'Analgesic', price: 7.99).save(failOnError: true)
        new Medication(name: 'Ibuprofen Tablet', category: 'Analgesic', price: 8.99).save(failOnError: true)
        new Medication(name: 'Amoxicillin Capsule', category: 'Antibiotic', price: 12.99).save(failOnError: true)

        when:
        def results = medicationService.searchMedications('Aspirin')

        then:
        results.size() == 2
        results*.name.every { it.contains('Aspirin') }
    }

    void "test searchTop medications via service"() {
        given:
        new Medication(name: 'Metformin 500mg', category: 'Antidiabetic', price: 10.00).save(failOnError: true)
        new Medication(name: 'Metformin 1000mg', category: 'Antidiabetic', price: 15.00).save(failOnError: true)
        new Medication(name: 'Metformin XR', category: 'Antidiabetic', price: 20.00).save(failOnError: true)
        new Medication(name: 'Insulin Glargine', category: 'Antidiabetic', price: 150.00).save(failOnError: true)

        when:
        def results = medicationService.searchTopMedications('Metformin', 2)

        then:
        results.size() == 2
        results*.name.every { it.contains('Metformin') }
    }

    void "test countHits medications via service"() {
        given:
        new Medication(name: 'Lisinopril 10mg', category: 'ACE Inhibitor', price: 6.00).save(failOnError: true)
        new Medication(name: 'Lisinopril 20mg', category: 'ACE Inhibitor', price: 8.00).save(failOnError: true)
        new Medication(name: 'Losartan 50mg', category: 'ARB', price: 10.00).save(failOnError: true)

        when:
        def count = medicationService.countMedicationHits('Lisinopril')

        then:
        count == 2
    }
}
