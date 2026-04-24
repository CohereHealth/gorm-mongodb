package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j
import org.bson.Document

@Slf4j
class MedicationService {

    // ---- Bson find / count / findOneAndDelete (on Facility) ----

    def findFacilitiesByBsonFilter(Map filter) {
        return Facility.withNativeTransaction { session ->
            Facility.find(new Document(filter)).into([])
        }
    }

    def countFacilitiesByBsonFilter(Map filter) {
        return Facility.withNativeTransaction { session ->
            Facility.count(new Document(filter))
        }
    }

    def findOneAndDeleteFacility(Map filter) {
        return Facility.withNativeTransaction { session ->
            Facility.findOneAndDelete(new Document(filter))
        }
    }

    // ---- Medication search / searchTop / countHits ----

    def searchMedications(String query) {
        return Medication.withNativeTransaction { session ->
            Medication.search(query)
        }
    }

    def searchTopMedications(String query, int limit) {
        return Medication.withNativeTransaction { session ->
            Medication.searchTop(query, limit)
        }
    }

    def countMedicationHits(String query) {
        return Medication.withNativeTransaction { session ->
            Medication.countHits(query)
        }
    }
}
