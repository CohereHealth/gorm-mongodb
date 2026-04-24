package example

import grails.gorm.transactions.Transactional
import groovy.util.logging.Slf4j

@Slf4j
@Transactional
class ReferralService {

    def createReferralWithAudit(Map referralData) {
        return Referral.withNativeTransaction { session ->
            def referral = new Referral(referralData + [status: 'PENDING']).save(failOnError: true)

            AuditEvent.withNewNativeTransaction { auditSession ->
                new AuditEvent(
                    action: 'CREATE_REFERRAL',
                    entityType: 'Referral',
                    entityId: referral.id?.toString(),
                    performedBy: 'SYSTEM',
                    timestamp: new Date(),
                    changes: [
                        detail: "Referral from ${referralData.fromFacility} to ${referralData.toFacility}",
                        outcome: 'SUCCESS'
                    ]
                ).save(failOnError: true)
            }

            return referral
        }
    }

    def createReferralWithAuditAndFailure(Map referralData) {
        Referral.withNativeTransaction { session ->
            def referral = new Referral(referralData + [status: 'PENDING']).save(failOnError: true)

            AuditEvent.withNewNativeTransaction { auditSession ->
                new AuditEvent(
                    action: 'CREATE_REFERRAL_ATTEMPT',
                    entityType: 'Referral',
                    entityId: referral.id?.toString(),
                    performedBy: 'SYSTEM',
                    timestamp: new Date(),
                    changes: [
                        detail: "Attempted referral from ${referralData.fromFacility} to ${referralData.toFacility}",
                        outcome: 'FAILURE'
                    ]
                ).save(failOnError: true)
            }

            throw new RuntimeException("Simulated failure after audit")
        }
    }

    def updateReferralStatusWithAudit(Serializable referralId, String newStatus) {
        return Referral.withNativeTransaction { session ->
            def referral = Referral.get(referralId)
            def oldStatus = referral.status
            referral.status = newStatus
            referral.save(failOnError: true)

            AuditEvent.withNewNativeTransaction { auditSession ->
                new AuditEvent(
                    action: 'UPDATE_REFERRAL_STATUS',
                    entityType: 'Referral',
                    entityId: referralId.toString(),
                    performedBy: 'SYSTEM',
                    timestamp: new Date(),
                    changes: [
                        detail: "Status changed from ${oldStatus} to ${newStatus}",
                        outcome: 'SUCCESS'
                    ]
                ).save(failOnError: true)
            }

            return referral
        }
    }

    def failedStatusUpdateWithAudit(Serializable referralId, String newStatus) {
        Referral.withNativeTransaction { session ->
            def referral = Referral.get(referralId)
            def oldStatus = referral.status

            AuditEvent.withNewNativeTransaction { auditSession ->
                new AuditEvent(
                    action: 'UPDATE_REFERRAL_STATUS_ATTEMPT',
                    entityType: 'Referral',
                    entityId: referralId.toString(),
                    performedBy: 'SYSTEM',
                    timestamp: new Date(),
                    changes: [
                        detail: "Attempted status change from ${oldStatus} to ${newStatus}",
                        outcome: 'FAILURE'
                    ]
                ).save(failOnError: true)
            }

            referral.status = newStatus
            referral.save(failOnError: true)
            throw new RuntimeException("Simulated failure after status update")
        }
    }

    def independentTransactions(Map referralData1, Map referralData2, boolean failSecond) {
        def results = [:]

        Referral.withNewNativeTransaction { session ->
            results.first = new Referral(referralData1 + [status: 'PENDING']).save(failOnError: true)

            try {
                AuditEvent.withNewNativeTransaction { innerSession ->
                    results.second = new AuditEvent(
                        action: 'INDEPENDENT_CREATE',
                        entityType: 'Referral',
                        entityId: results.first.id?.toString() ?: 'UNKNOWN',
                        performedBy: 'SYSTEM',
                        timestamp: new Date(),
                        changes: [
                            detail: "Independent referral from ${referralData2.fromFacility} to ${referralData2.toFacility}",
                            outcome: 'SUCCESS'
                        ]
                    ).save(failOnError: true)

                    if (failSecond) {
                        throw new RuntimeException("Inner transaction failure")
                    }
                }
            } catch (RuntimeException e) {
                results.innerError = e.message
            }

            return results
        }
    }

    def innerCommitsOuterRollsBack(Map referralData) {
        Referral.withNativeTransaction { session ->
            AuditEvent.withNewNativeTransaction { innerSession ->
                new AuditEvent(
                    action: 'INNER_COMMIT_TEST',
                    entityType: 'Referral',
                    entityId: referralData.patientName ?: 'UNKNOWN',
                    performedBy: 'SYSTEM',
                    timestamp: new Date(),
                    changes: [
                        detail: "Inner commit for ${referralData.patientName}",
                        outcome: 'SUCCESS'
                    ]
                ).save(failOnError: true)
            }

            throw new RuntimeException("Outer rolls back")
        }
    }
}
