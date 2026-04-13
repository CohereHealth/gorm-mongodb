package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

@Integration
@NativeRollback
class NativeQueryIntegrationSpec extends Specification {

    FacilityService facilityService

    void "test criteria query with eq and order via service"() {
        given:
        new Diagnosis(icdCode: 'J06.9', description: 'Upper respiratory infection', category: 'Respiratory', severity: 'LOW').save(failOnError: true)
        new Diagnosis(icdCode: 'J18.9', description: 'Pneumonia', category: 'Respiratory', severity: 'HIGH').save(failOnError: true)
        new Diagnosis(icdCode: 'E11.9', description: 'Type 2 diabetes', category: 'Endocrine', severity: 'MODERATE').save(failOnError: true)

        when:
        def results = facilityService.findDiagnosesByCategory('Respiratory')

        then:
        results.size() == 2
        results*.category.every { it == 'Respiratory' }
    }

    void "test criteria query with ge and le via service"() {
        given:
        new Facility(name: 'Small Clinic', npi: 'NPI001', facilityType: 'CLINIC', bedCount: 10).save(failOnError: true)
        new Facility(name: 'Regional Hospital', npi: 'NPI002', facilityType: 'HOSPITAL', bedCount: 200).save(failOnError: true)
        new Facility(name: 'Large Hospital', npi: 'NPI003', facilityType: 'HOSPITAL', bedCount: 500).save(failOnError: true)

        when:
        def results = facilityService.findFacilitiesByBedRange(100, 300)

        then:
        results.size() == 1
        results[0].name == 'Regional Hospital'
    }

    void "test criteria query with like via service"() {
        given:
        new Diagnosis(icdCode: 'J06.9', description: 'Upper respiratory infection', category: 'Respiratory', severity: 'LOW').save(failOnError: true)
        new Diagnosis(icdCode: 'J18.9', description: 'Community-acquired pneumonia', category: 'Respiratory', severity: 'HIGH').save(failOnError: true)
        new Diagnosis(icdCode: 'I10', description: 'Essential hypertension', category: 'Cardiovascular', severity: 'MODERATE').save(failOnError: true)

        when:
        def results = facilityService.findDiagnosesByIcdPrefix('J')

        then:
        results.size() == 2
        results*.icdCode.every { it.startsWith('J') }
    }

    void "test criteria query with in list via service"() {
        given:
        new Diagnosis(icdCode: 'J06.9', description: 'URI', category: 'Respiratory', severity: 'LOW').save(failOnError: true)
        new Diagnosis(icdCode: 'I10', description: 'Hypertension', category: 'Cardiovascular', severity: 'HIGH').save(failOnError: true)
        new Diagnosis(icdCode: 'E11.9', description: 'Diabetes', category: 'Endocrine', severity: 'CRITICAL').save(failOnError: true)

        when:
        def results = facilityService.findDiagnosesBySeverities(['HIGH', 'CRITICAL'])

        then:
        results.size() == 2
        results*.severity.every { it in ['HIGH', 'CRITICAL'] }
    }

    void "test criteria query with projections via service"() {
        given:
        new Facility(name: 'Mercy Hospital', npi: 'NPI010', facilityType: 'HOSPITAL', bedCount: 300).save(failOnError: true)
        new Facility(name: 'City Clinic', npi: 'NPI011', facilityType: 'CLINIC', bedCount: 20).save(failOnError: true)
        new Facility(name: 'County Hospital', npi: 'NPI012', facilityType: 'HOSPITAL', bedCount: 450).save(failOnError: true)

        expect:
        facilityService.getMaxBedCount() == 450
        facilityService.getTotalBedCount() == 770
        facilityService.getFacilityCount() == 3
    }

    void "test criteria query with distinct projection via service"() {
        given:
        new Diagnosis(icdCode: 'J06.9', description: 'URI', category: 'Respiratory', severity: 'LOW').save(failOnError: true)
        new Diagnosis(icdCode: 'J18.9', description: 'Pneumonia', category: 'Respiratory', severity: 'HIGH').save(failOnError: true)
        new Diagnosis(icdCode: 'I10', description: 'Hypertension', category: 'Cardiovascular', severity: 'MODERATE').save(failOnError: true)
        new Diagnosis(icdCode: 'I25.10', description: 'CAD', category: 'Cardiovascular', severity: 'HIGH').save(failOnError: true)

        when:
        def categories = facilityService.getDistinctCategories()

        then:
        categories.size() == 2
        categories.containsAll(['Respiratory', 'Cardiovascular'])
    }

    void "test aggregation with group and sum via service"() {
        given:
        new Facility(name: 'Hospital A', npi: 'NPI020', facilityType: 'HOSPITAL', bedCount: 200).save(failOnError: true)
        new Facility(name: 'Hospital B', npi: 'NPI021', facilityType: 'HOSPITAL', bedCount: 350).save(failOnError: true)
        new Facility(name: 'Clinic A', npi: 'NPI022', facilityType: 'CLINIC', bedCount: 15).save(failOnError: true)

        when:
        def results = facilityService.aggregateFacilitiesByType()

        then:
        results.size() == 2
        results[0]._id == 'HOSPITAL'
        results[0].totalBeds == 550
        results[0].count == 2
        results[1]._id == 'CLINIC'
        results[1].totalBeds == 15
    }

    void "test aggregation with match and group via service"() {
        given:
        new Diagnosis(icdCode: 'J06.9', description: 'URI', category: 'Respiratory', severity: 'LOW').save(failOnError: true)
        new Diagnosis(icdCode: 'J18.9', description: 'Pneumonia', category: 'Respiratory', severity: 'HIGH').save(failOnError: true)
        new Diagnosis(icdCode: 'I10', description: 'Hypertension', category: 'Cardiovascular', severity: 'HIGH').save(failOnError: true)
        new Diagnosis(icdCode: 'E11.9', description: 'Diabetes', category: 'Endocrine', severity: 'MODERATE').save(failOnError: true)

        when:
        def results = facilityService.aggregateDiagnosesBySeverity('HIGH')

        then:
        results.size() == 2
        results.collect { it._id }.containsAll(['Respiratory', 'Cardiovascular'])
        results.every { it.count == 1 }
    }

    void "test aggregation with project via service"() {
        given:
        new Procedure(cptCode: '99213', name: 'Office Visit', cost: 150.00,
            diagnosis: new Diagnosis(icdCode: 'J06.9', description: 'URI', category: 'Respiratory', severity: 'LOW').save(failOnError: true)
        ).save(failOnError: true)
        new Procedure(cptCode: '99214', name: 'Extended Visit', cost: 250.00,
            diagnosis: new Diagnosis(icdCode: 'I10', description: 'Hypertension', category: 'Cardiovascular', severity: 'MODERATE').save(failOnError: true)
        ).save(failOnError: true)

        when:
        def results = facilityService.aggregateProcedureCosts()

        then:
        results.size() == 2
        results[0].name == 'Office Visit'
        results[0].cost == 150.00
        results[1].name == 'Extended Visit'
    }

    void "test aggregation on embedded field via service"() {
        given:
        new Facility(name: 'H1', npi: 'NPI040', facilityType: 'HOSPITAL', bedCount: 200,
            address: new FacilityAddress(street: '1 St', city: 'Houston', state: 'TX', zipCode: '77001')
        ).save(failOnError: true)
        new Facility(name: 'H2', npi: 'NPI041', facilityType: 'HOSPITAL', bedCount: 300,
            address: new FacilityAddress(street: '2 St', city: 'Dallas', state: 'TX', zipCode: '75201')
        ).save(failOnError: true)
        new Facility(name: 'H3', npi: 'NPI042', facilityType: 'HOSPITAL', bedCount: 150,
            address: new FacilityAddress(street: '3 St', city: 'Chicago', state: 'IL', zipCode: '60601')
        ).save(failOnError: true)

        when:
        def results = facilityService.aggregateFacilitiesByState()

        then:
        results.size() == 2
        results[0]._id == 'TX'
        results[0].totalBeds == 500
        results[1]._id == 'IL'
        results[1].totalBeds == 150
    }

    void "test aggregation diagnoses by category via service"() {
        given:
        new Diagnosis(icdCode: 'J06.9', description: 'URI', category: 'Respiratory', severity: 'LOW').save(failOnError: true)
        new Diagnosis(icdCode: 'J18.9', description: 'Pneumonia', category: 'Respiratory', severity: 'HIGH').save(failOnError: true)
        new Diagnosis(icdCode: 'I10', description: 'Hypertension', category: 'Cardiovascular', severity: 'MODERATE').save(failOnError: true)

        when:
        def results = facilityService.aggregateDiagnosesByCategory()

        then:
        results.size() == 2
        results.find { it._id == 'Respiratory' }.count == 2
        results.find { it._id == 'Cardiovascular' }.count == 1
    }

    void "test service creates facility with embedded address"() {
        when:
        def facility = facilityService.createFacilityWithAddress(
            'Johns Hopkins', 'NPI050', 'HOSPITAL', 1000,
            [street: '1800 Orleans St', city: 'Baltimore', state: 'MD', zipCode: '21287']
        )

        then:
        facility != null
        facility.id != null
        facility.address.city == 'Baltimore'
        Facility.get(facility.id).address.state == 'MD'
    }

    void "test service finds facilities by embedded city"() {
        given:
        new Facility(name: 'F1', npi: 'NPI051', facilityType: 'HOSPITAL', bedCount: 100,
            address: new FacilityAddress(street: '1 St', city: 'Boston', state: 'MA', zipCode: '02101')
        ).save(failOnError: true)
        new Facility(name: 'F2', npi: 'NPI052', facilityType: 'CLINIC', bedCount: 20,
            address: new FacilityAddress(street: '2 St', city: 'Boston', state: 'MA', zipCode: '02102')
        ).save(failOnError: true)
        new Facility(name: 'F3', npi: 'NPI053', facilityType: 'HOSPITAL', bedCount: 200,
            address: new FacilityAddress(street: '3 St', city: 'New York', state: 'NY', zipCode: '10001')
        ).save(failOnError: true)

        when:
        def results = facilityService.findFacilitiesByCity('Boston')

        then:
        results.size() == 2
        results*.name.containsAll(['F1', 'F2'])
    }

    void "test service finds facilities by embedded state"() {
        given:
        new Facility(name: 'Facility A', npi: 'NPI033', facilityType: 'HOSPITAL', bedCount: 100,
            address: new FacilityAddress(street: '1 St', city: 'Houston', state: 'TX', zipCode: '77001')
        ).save(failOnError: true)
        new Facility(name: 'Facility B', npi: 'NPI034', facilityType: 'CLINIC', bedCount: 20,
            address: new FacilityAddress(street: '2 St', city: 'Dallas', state: 'TX', zipCode: '75201')
        ).save(failOnError: true)
        new Facility(name: 'Facility C', npi: 'NPI035', facilityType: 'HOSPITAL', bedCount: 300,
            address: new FacilityAddress(street: '3 St', city: 'Chicago', state: 'IL', zipCode: '60601')
        ).save(failOnError: true)

        when:
        def results = facilityService.findFacilitiesByState('TX')

        then:
        results.size() == 2
        results[0].name == 'Facility A'
        results[1].name == 'Facility B'
    }

    void "test service updates embedded address"() {
        given:
        def facility = new Facility(name: 'Test Facility', npi: 'NPI032', facilityType: 'CLINIC', bedCount: 10,
            address: new FacilityAddress(street: '100 Main St', city: 'Austin', state: 'TX', zipCode: '73301')
        ).save(failOnError: true)

        when:
        facilityService.updateFacilityAddress(facility.id, [city: 'Dallas', zipCode: '75201'])

        then:
        def reloaded = Facility.get(facility.id)
        reloaded.address.city == 'Dallas'
        reloaded.address.zipCode == '75201'
        reloaded.address.state == 'TX'
    }

    void "test service finds facilities by type and min beds"() {
        given:
        new Facility(name: 'Big Hospital', npi: 'NPI060', facilityType: 'HOSPITAL', bedCount: 500).save(failOnError: true)
        new Facility(name: 'Small Hospital', npi: 'NPI061', facilityType: 'HOSPITAL', bedCount: 50).save(failOnError: true)
        new Facility(name: 'Clinic', npi: 'NPI062', facilityType: 'CLINIC', bedCount: 10).save(failOnError: true)

        when:
        def results = facilityService.findFacilitiesByTypeAndMinBeds('HOSPITAL', 100)

        then:
        results.size() == 1
        results[0].name == 'Big Hospital'
    }

    void "test service creates diagnosis with procedures"() {
        when:
        def diagnosis = facilityService.createDiagnosisWithProcedures(
            [icdCode: 'M54.5', description: 'Low back pain', category: 'Musculoskeletal', severity: 'MODERATE'],
            [
                [cptCode: '97110', name: 'Therapeutic exercises', cost: 75.00],
                [cptCode: '97140', name: 'Manual therapy', cost: 85.00]
            ]
        )

        then:
        diagnosis != null
        diagnosis.procedures.size() == 2
        Procedure.countByDiagnosis(diagnosis) == 2

        and:
        def reloaded = Diagnosis.get(diagnosis.id)
        reloaded.procedures.size() == 2
    }

    void "test service finds procedures by diagnosis"() {
        given:
        def diag1 = new Diagnosis(icdCode: 'J06.9', description: 'URI', category: 'Respiratory', severity: 'LOW').save(failOnError: true)
        def diag2 = new Diagnosis(icdCode: 'M54.5', description: 'Back pain', category: 'Musculoskeletal', severity: 'MODERATE').save(failOnError: true)
        new Procedure(cptCode: '99213', name: 'Office Visit', cost: 150.00, diagnosis: diag1).save(failOnError: true)
        new Procedure(cptCode: '97110', name: 'Therapeutic exercises', cost: 75.00, diagnosis: diag2).save(failOnError: true)
        new Procedure(cptCode: '97140', name: 'Manual therapy', cost: 85.00, diagnosis: diag2).save(failOnError: true)

        when:
        def results = facilityService.findProceduresByDiagnosis(diag2)

        then:
        results.size() == 2
        results*.name.containsAll(['Therapeutic exercises', 'Manual therapy'])
    }

    void "test service finds expensive procedures by diagnosis"() {
        given:
        def diag = new Diagnosis(icdCode: 'I10', description: 'Hypertension', category: 'Cardiovascular', severity: 'HIGH').save(failOnError: true)
        new Procedure(cptCode: '93000', name: 'ECG', cost: 50.00, diagnosis: diag).save(failOnError: true)
        new Procedure(cptCode: '93306', name: 'Echocardiogram', cost: 500.00, diagnosis: diag).save(failOnError: true)
        new Procedure(cptCode: '99213', name: 'Office Visit', cost: 150.00, diagnosis: diag).save(failOnError: true)

        when:
        def results = facilityService.findExpensiveProcedures(diag, 100.00)

        then:
        results.size() == 2
        results[0].name == 'Echocardiogram'
        results[1].name == 'Office Visit'
    }

    void "test service removes procedure from diagnosis"() {
        given:
        def diagnosis = new Diagnosis(icdCode: 'E11.9', description: 'Diabetes', category: 'Endocrine', severity: 'MODERATE').save(failOnError: true)
        def proc = new Procedure(cptCode: '82947', name: 'Glucose test', cost: 25.00)
        diagnosis.addToProcedures(proc)
        diagnosis.save(failOnError: true)

        when:
        facilityService.removeProcedureFromDiagnosis(diagnosis, proc)

        then:
        Procedure.countByDiagnosis(diagnosis) == 0
        Diagnosis.get(diagnosis.id) != null
    }

    void "test rollback reverts diagnosis and procedures"() {
        given:
        def initialDiagCount = Diagnosis.count()
        def initialProcCount = Procedure.count()

        when:
        try {
            Diagnosis.withNativeTransaction { session ->
                def diag = new Diagnosis(icdCode: 'Z00.00', description: 'General exam', category: 'Preventive', severity: 'LOW')
                diag.save(failOnError: true)
                diag.addToProcedures(new Procedure(cptCode: '99395', name: 'Preventive visit', cost: 200.00))
                diag.save(failOnError: true)
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException e) {
        }

        then:
        Diagnosis.count() == initialDiagCount
        Procedure.count() == initialProcCount
    }

    void "test rollback reverts facility with embedded address"() {
        given:
        def initialCount = Facility.count()

        when:
        try {
            Facility.withNativeTransaction { session ->
                new Facility(name: 'Rollback Hospital', npi: 'NPI099', facilityType: 'HOSPITAL', bedCount: 100,
                    address: new FacilityAddress(street: '1 St', city: 'Nowhere', state: 'XX', zipCode: '00000')
                ).save(failOnError: true)
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException e) {
        }

        then:
        Facility.count() == initialCount
        Facility.findByNpi('NPI099') == null
    }
}
