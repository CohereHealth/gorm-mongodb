package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

@Integration
@NativeRollback
class EmbeddedIntegrationSpec extends Specification {

    FacilityService facilityService

    void "test create facility with embedded address via service"() {
        when:
        def facility = facilityService.createFacilityWithAddress(
            'Mayo Clinic', 'NPI100', 'HOSPITAL', 2000,
            [street: '200 First St SW', city: 'Rochester', state: 'MN', zipCode: '55905']
        )

        then:
        facility.id != null
        facility.address != null
        facility.address.street == '200 First St SW'
        facility.address.city == 'Rochester'
        facility.address.state == 'MN'
        facility.address.zipCode == '55905'

        and:
        def reloaded = Facility.get(facility.id)
        reloaded.address.city == 'Rochester'
    }

    void "test create facility without embedded address"() {
        when:
        def facility = new Facility(name: 'No Address', npi: 'NPI101', facilityType: 'CLINIC', bedCount: 5).save(failOnError: true)

        then:
        facility.id != null
        facility.address == null

        and:
        Facility.get(facility.id).address == null
    }

    void "test update embedded address fields via service"() {
        given:
        def facility = new Facility(name: 'Test', npi: 'NPI102', facilityType: 'CLINIC', bedCount: 10,
            address: new FacilityAddress(street: '100 Main St', city: 'Austin', state: 'TX', zipCode: '73301')
        ).save(failOnError: true)

        when:
        facilityService.updateFacilityAddress(facility.id, [city: 'Dallas', zipCode: '75201'])

        then:
        def reloaded = Facility.get(facility.id)
        reloaded.address.city == 'Dallas'
        reloaded.address.zipCode == '75201'
        reloaded.address.street == '100 Main St'
        reloaded.address.state == 'TX'
    }

    void "test replace entire embedded address via service"() {
        given:
        def facility = new Facility(name: 'Replace Test', npi: 'NPI103', facilityType: 'HOSPITAL', bedCount: 100,
            address: new FacilityAddress(street: '1 Old St', city: 'OldCity', state: 'OC', zipCode: '00000')
        ).save(failOnError: true)

        when:
        facilityService.replaceFacilityAddress(facility.id,
            [street: '2 New Ave', city: 'NewCity', state: 'NC', zipCode: '99999'])

        then:
        def reloaded = Facility.get(facility.id)
        reloaded.address.street == '2 New Ave'
        reloaded.address.city == 'NewCity'
        reloaded.address.state == 'NC'
        reloaded.address.zipCode == '99999'
    }

    void "test set embedded address to null via service"() {
        given:
        def facility = new Facility(name: 'Null Test', npi: 'NPI104', facilityType: 'CLINIC', bedCount: 5,
            address: new FacilityAddress(street: '1 St', city: 'City', state: 'ST', zipCode: '11111')
        ).save(failOnError: true)

        when:
        facilityService.clearFacilityAddress(facility.id)

        then:
        Facility.get(facility.id).address == null
    }

    void "test criteria query on embedded field via service"() {
        given:
        new Facility(name: 'Houston Hospital', npi: 'NPI110', facilityType: 'HOSPITAL', bedCount: 200,
            address: new FacilityAddress(street: '1 St', city: 'Houston', state: 'TX', zipCode: '77001')
        ).save(failOnError: true)
        new Facility(name: 'Dallas Clinic', npi: 'NPI111', facilityType: 'CLINIC', bedCount: 20,
            address: new FacilityAddress(street: '2 St', city: 'Dallas', state: 'TX', zipCode: '75201')
        ).save(failOnError: true)
        new Facility(name: 'Chicago Hospital', npi: 'NPI112', facilityType: 'HOSPITAL', bedCount: 300,
            address: new FacilityAddress(street: '3 St', city: 'Chicago', state: 'IL', zipCode: '60601')
        ).save(failOnError: true)

        when:
        def byCity = facilityService.findFacilitiesByCity('Houston')

        then:
        byCity.size() == 1
        byCity[0].name == 'Houston Hospital'

        when:
        def byState = facilityService.findFacilitiesByState('TX')

        then:
        byState.size() == 2
        byState[0].name == 'Dallas Clinic'
        byState[1].name == 'Houston Hospital'
    }

    void "test criteria get single result by embedded zip via service"() {
        given:
        new Facility(name: 'Zip Facility', npi: 'NPI113', facilityType: 'PHARMACY', bedCount: 0,
            address: new FacilityAddress(street: '5 Rx Blvd', city: 'Pharma', state: 'PH', zipCode: '12345')
        ).save(failOnError: true)

        when:
        def result = facilityService.findFacilityByEmbeddedZip('12345')

        then:
        result != null
        result.name == 'Zip Facility'

        when:
        def noResult = facilityService.findFacilityByEmbeddedZip('99999')

        then:
        noResult == null
    }

    void "test aggregation on embedded field via service"() {
        given:
        new Facility(name: 'CA1', npi: 'NPI120', facilityType: 'HOSPITAL', bedCount: 200,
            address: new FacilityAddress(street: '1 St', city: 'LA', state: 'CA', zipCode: '90001')
        ).save(failOnError: true)
        new Facility(name: 'CA2', npi: 'NPI121', facilityType: 'HOSPITAL', bedCount: 300,
            address: new FacilityAddress(street: '2 St', city: 'SF', state: 'CA', zipCode: '94101')
        ).save(failOnError: true)
        new Facility(name: 'FL1', npi: 'NPI122', facilityType: 'HOSPITAL', bedCount: 100,
            address: new FacilityAddress(street: '3 St', city: 'Miami', state: 'FL', zipCode: '33101')
        ).save(failOnError: true)

        when:
        def results = facilityService.aggregateFacilitiesByState()

        then:
        results.size() == 2
        results[0]._id == 'CA'
        results[0].totalBeds == 500
        results[1]._id == 'FL'
        results[1].totalBeds == 100
    }

    void "test embedded address survives version increment"() {
        given:
        def facility = new Facility(name: 'Version Test', npi: 'NPI130', facilityType: 'HOSPITAL', bedCount: 50,
            address: new FacilityAddress(street: '1 St', city: 'VersionCity', state: 'VC', zipCode: '11111')
        ).save(failOnError: true)

        when:
        facility.bedCount = 100
        facility.save(failOnError: true)

        then:
        facility.version == old(facility.version) + 1
        facility.address.city == 'VersionCity'

        and:
        def reloaded = Facility.get(facility.id)
        reloaded.bedCount == 100
        reloaded.address.city == 'VersionCity'
    }

    void "test multiple updates to embedded address"() {
        given:
        def facility = new Facility(name: 'Multi Update', npi: 'NPI131', facilityType: 'CLINIC', bedCount: 10,
            address: new FacilityAddress(street: '1 St', city: 'First', state: 'AA', zipCode: '11111')
        ).save(failOnError: true)

        when:
        facilityService.updateFacilityAddress(facility.id, [city: 'Second'])

        then:
        Facility.get(facility.id).address.city == 'Second'

        when:
        facilityService.updateFacilityAddress(facility.id, [city: 'Third', state: 'BB'])

        then:
        def reloaded = Facility.get(facility.id)
        reloaded.address.city == 'Third'
        reloaded.address.state == 'BB'
        reloaded.address.street == '1 St'
    }

    void "test rollback reverts embedded address changes"() {
        given:
        def facility = new Facility(name: 'Rollback Embedded', npi: 'NPI140', facilityType: 'HOSPITAL', bedCount: 50,
            address: new FacilityAddress(street: '1 St', city: 'Original', state: 'OR', zipCode: '00000')
        ).save(failOnError: true)

        when:
        try {
            Facility.withNativeTransaction { session ->
                def f = Facility.get(facility.id)
                f.address.city = 'Changed'
                f.save(failOnError: true)
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException e) {
        }

        then:
        Facility.get(facility.id).address.city == 'Original'
    }

    void "test rollback reverts new facility with embedded address"() {
        when:
        try {
            Facility.withNativeTransaction { session ->
                new Facility(name: 'Rollback New', npi: 'NPI141', facilityType: 'HOSPITAL', bedCount: 100,
                    address: new FacilityAddress(street: '1 St', city: 'Gone', state: 'XX', zipCode: '00000')
                ).save(failOnError: true)
                throw new RuntimeException("Force rollback")
            }
        } catch (RuntimeException e) {
        }

        then:
        Facility.count() == old(Facility.count())
        Facility.findByNpi('NPI141') == null
    }
}
