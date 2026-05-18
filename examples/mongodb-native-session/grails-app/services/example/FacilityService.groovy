package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j
import org.bson.Document

@Slf4j
@Transactional
class FacilityService {

    def createFacilityWithAddress(String name, String npi, String type, Integer beds, Map addressData) {
        return Facility.withNativeTransaction { session ->
            new Facility(
                name: name, npi: npi, facilityType: type, bedCount: beds,
                address: new FacilityAddress(addressData)
            ).save(failOnError: true)
        }
    }

    def updateFacilityAddress(Serializable facilityId, Map addressUpdates) {
        return Facility.withNativeTransaction { session ->
            def facility = Facility.get(facilityId)
            addressUpdates.each { k, v -> facility.address[k] = v }
            facility.save(failOnError: true)
        }
    }

    def replaceFacilityAddress(Serializable facilityId, Map newAddressData) {
        return Facility.withNativeTransaction { session ->
            def facility = Facility.get(facilityId)
            facility.address = new FacilityAddress(newAddressData)
            facility.save(failOnError: true)
        }
    }

    def clearFacilityAddress(Serializable facilityId) {
        return Facility.withNativeTransaction { session ->
            def facility = Facility.get(facilityId)
            facility.address = null
            facility.save(failOnError: true)
        }
    }

    def findFacilityByEmbeddedZip(String zipCode) {
        return Facility.withNativeTransaction { session ->
            Facility.createCriteria().get {
                address {
                    eq('zipCode', zipCode)
                }
            }
        }
    }

    def projectEmbeddedAddresses() {
        return Facility.withNativeTransaction { session ->
            Facility.createCriteria().list {
                projections {
                    property('address')
                }
            }
        }
    }

    def findFacilitiesByCity(String city) {
        return Facility.withNativeTransaction { session ->
            Facility.createCriteria().list {
                address {
                    eq('city', city)
                }
            }
        }
    }

    def findFacilitiesByState(String state) {
        return Facility.withNativeTransaction { session ->
            Facility.createCriteria().list {
                address {
                    eq('state', state)
                }
                order('name', 'asc')
            }
        }
    }

    def findFacilitiesByTypeAndMinBeds(String type, Integer minBeds) {
        return Facility.withNativeTransaction { session ->
            Facility.createCriteria().list {
                eq('facilityType', type)
                ge('bedCount', minBeds)
                order('bedCount', 'desc')
            }
        }
    }

    def findFacilitiesByBedRange(Integer min, Integer max) {
        return Facility.withNativeTransaction { session ->
            Facility.createCriteria().list {
                ge('bedCount', min)
                le('bedCount', max)
            }
        }
    }

    def getMaxBedCount() {
        return Facility.withNativeTransaction { session ->
            Facility.createCriteria().get {
                projections { max('bedCount') }
            }
        }
    }

    def getTotalBedCount() {
        return Facility.withNativeTransaction { session ->
            Facility.createCriteria().get {
                projections { sum('bedCount') }
            }
        }
    }

    def getFacilityCount() {
        return Facility.withNativeTransaction { session ->
            Facility.createCriteria().get {
                projections { rowCount() }
            }
        }
    }

    def aggregateFacilitiesByType() {
        return Facility.withNativeTransaction { session ->
            Facility.aggregate([
                ['$group': [_id: '$facilityType', totalBeds: ['$sum': '$bedCount'], count: ['$sum': 1]]],
                ['$sort': [totalBeds: -1]]
            ], Document)
        }
    }

    def aggregateFacilitiesByState() {
        return Facility.withNativeTransaction { session ->
            Facility.aggregate([
                ['$group': [_id: '$address.state', totalBeds: ['$sum': '$bedCount'], count: ['$sum': 1]]],
                ['$sort': [totalBeds: -1]]
            ], Document)
        }
    }

    def findDiagnosesByCategory(String category) {
        return Diagnosis.withNativeTransaction { session ->
            Diagnosis.createCriteria().list {
                eq('category', category)
                order('severity', 'asc')
            }
        }
    }

    def findDiagnosesByIcdPrefix(String prefix) {
        return Diagnosis.withNativeTransaction { session ->
            Diagnosis.createCriteria().list {
                like('icdCode', "${prefix}%")
            }
        }
    }

    def findDiagnosesBySeverities(List<String> severities) {
        return Diagnosis.withNativeTransaction { session ->
            Diagnosis.createCriteria().list {
                'in'('severity', severities)
            }
        }
    }

    def getDistinctCategories() {
        return Diagnosis.withNativeTransaction { session ->
            Diagnosis.createCriteria().list {
                projections { distinct('category') }
            }
        }
    }

    def aggregateDiagnosesByCategory() {
        return Diagnosis.withNativeTransaction { session ->
            Diagnosis.aggregate([
                ['$group': [_id: '$category', count: ['$sum': 1]]],
                ['$sort': [count: -1]]
            ], Document)
        }
    }

    def aggregateDiagnosesBySeverity(String severity) {
        return Diagnosis.withNativeTransaction { session ->
            Diagnosis.aggregate([
                ['$match': [severity: severity]],
                ['$group': [_id: '$category', count: ['$sum': 1]]]
            ], Document)
        }
    }

    def findProceduresByDiagnosis(Diagnosis diagnosis) {
        return Procedure.withNativeTransaction { session ->
            Procedure.findAllByDiagnosis(diagnosis)
        }
    }

    def findExpensiveProcedures(Diagnosis diagnosis, BigDecimal minCost) {
        return Procedure.withNativeTransaction { session ->
            Procedure.createCriteria().list {
                eq('diagnosis', diagnosis)
                ge('cost', minCost)
                order('cost', 'desc')
            }
        }
    }

    def aggregateProcedureCosts() {
        return Procedure.withNativeTransaction { session ->
            Procedure.aggregate([
                ['$project': [name: 1, cost: 1, _id: 0]],
                ['$sort': [cost: 1]]
            ], Document)
        }
    }

    def createDiagnosisWithProcedures(Map diagnosisData, List<Map> procedureDataList) {
        return Diagnosis.withNativeTransaction { session ->
            def diagnosis = new Diagnosis(diagnosisData).save(failOnError: true)
            procedureDataList.each { data ->
                diagnosis.addToProcedures(new Procedure(data))
            }
            diagnosis.save(failOnError: true)
        }
    }

    def removeProcedureFromDiagnosis(Diagnosis diagnosis, Procedure procedure) {
        return Diagnosis.withNativeTransaction { session ->
            diagnosis.removeFromProcedures(procedure)
            procedure.delete()
            diagnosis.save(failOnError: true)
        }
    }
}
