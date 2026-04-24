package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.mongo.NativeRollback
import spock.lang.Specification

@Integration
@NativeRollback
class ReferralServiceIntegrationSpec extends Specification {

    ReferralService referralService

    void cleanup() {
        AuditEvent.withNewNativeTransaction {
            AuditEvent.collection.drop()
        }
        Referral.withNewNativeTransaction {
            Referral.collection.drop()
        }
    }

    void "test create referral with audit in independent transaction"() {
        when:
        def referral = referralService.createReferralWithAudit(
            fromFacility: 'Hospital A', toFacility: 'Hospital B',
            patientName: 'John Doe', reason: 'Cardiology consult'
        )

        def audit = null
        AuditEvent.withNewNativeTransaction {
            audit = AuditEvent.findByEntityId(referral.id.toString())
        }

        then:
        referral != null
        referral.id != null
        referral.status == 'PENDING'
        audit != null
        audit.action == 'CREATE_REFERRAL'
    }

    void "test audit persists when referral transaction rolls back"() {
        when:
        try {
            referralService.createReferralWithAuditAndFailure(
                fromFacility: 'Clinic X', toFacility: 'Clinic Y',
                patientName: 'Jane Smith', reason: 'Orthopedic eval'
            )
        } catch (RuntimeException e) {
        }

        def audit = null
        AuditEvent.withNewNativeTransaction {
            audit = AuditEvent.findByAction('CREATE_REFERRAL_ATTEMPT')
        }

        then:
        Referral.findByPatientName('Jane Smith') == null
        audit != null
        audit.changes.outcome == 'FAILURE'
    }

    void "test update referral status with audit"() {
        given:
        def referral = new Referral(
            fromFacility: 'Hospital C', toFacility: 'Hospital D',
            patientName: 'Bob Wilson', reason: 'Neurology', status: 'PENDING'
        ).save(failOnError: true)

        when:
        referralService.updateReferralStatusWithAudit(referral.id, 'ACCEPTED')

        def audit = null
        AuditEvent.withNewNativeTransaction {
            audit = AuditEvent.findByAction('UPDATE_REFERRAL_STATUS')
        }

        then:
        Referral.get(referral.id).status == 'ACCEPTED'
        audit != null
        audit.changes.detail.contains('PENDING')
        audit.changes.detail.contains('ACCEPTED')
    }

    void "test audit persists when status update rolls back"() {
        given:
        def referral = new Referral(
                fromFacility: 'Hospital E', toFacility: 'Hospital F',
                patientName: 'Alice Brown', reason: 'Oncology', status: 'PENDING'
        ).save(failOnError: true)

        when:
        try {
            referralService.failedStatusUpdateWithAudit(referral.id, 'REJECTED')
        } catch (RuntimeException e) {
        }

        def audit = null
        AuditEvent.withNewNativeTransaction {
            audit = AuditEvent.findByAction('UPDATE_REFERRAL_STATUS_ATTEMPT')
        }

        then:
        audit != null
        audit.changes.outcome == 'FAILURE'
    }

    void "test independent transactions - inner fails outer succeeds"() {
        when:
        def results = referralService.independentTransactions(
            [fromFacility: 'A', toFacility: 'B', patientName: 'P1', reason: 'R1'],
            [fromFacility: 'C', toFacility: 'D', patientName: 'P2', reason: 'R2'],
            true
        )

        def referral = null
        Referral.withNewNativeTransaction {
            referral = Referral.findByPatientName('P1')
        }

        def audit = null
        AuditEvent.withNewNativeTransaction {
            audit = AuditEvent.findByAction('INDEPENDENT_CREATE')
        }

        then:
        results.innerError == 'Inner transaction failure'
        referral != null
        audit == null
    }

    void "test independent transactions - both succeed"() {
        when:
        def results = referralService.independentTransactions(
            [fromFacility: 'E', toFacility: 'F', patientName: 'P3', reason: 'R3'],
            [fromFacility: 'G', toFacility: 'H', patientName: 'P4', reason: 'R4'],
            false
        )

        def referral = null
        Referral.withNewNativeTransaction {
            referral = Referral.findByPatientName('P3')
        }

        def audit = null
        AuditEvent.withNewNativeTransaction {
            audit = AuditEvent.findByAction('INDEPENDENT_CREATE')
        }

        then:
        referral != null
        audit != null
    }

    void "test inner commits but outer rolls back"() {
        when:
        try {
            referralService.innerCommitsOuterRollsBack(
                patientName: 'Inner Patient'
            )
        } catch (RuntimeException e) {
        }

        def audit = null
        AuditEvent.withNewNativeTransaction {
            audit = AuditEvent.findByAction('INNER_COMMIT_TEST')
        }

        then:
        audit != null
        audit.changes.detail.contains('Inner Patient')
    }

    void "test new native transaction does not see outer uncommitted writes"() {
        when:
        def outerVisible = null
        def innerVisible = null

        Referral.withNewNativeTransaction { outerSession ->
            new Referral(
                fromFacility: 'Outer', toFacility: 'Only',
                patientName: 'Invisible', reason: 'Snapshot isolation', status: 'PENDING'
            ).save(failOnError: true)

            outerVisible = Referral.findByPatientName('Invisible')

            Referral.withNewNativeTransaction { innerSession ->
                innerVisible = Referral.findByPatientName('Invisible')
            }
        }

        then:
        outerVisible != null
        innerVisible == null
    }
}
