package example

import grails.testing.mixin.integration.Integration
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.transactions.SessionHolder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

import java.time.LocalDateTime

/**
 * Reproduces the failure: a regular {@code @Transactional} service is
 * invoked inside a native MongoDB transaction WHILE a plain GORM session is already bound to
 * the datastore — exactly what Grails' {@code OpenSessionInViewInterceptor} does on every web
 * request.
 *
 * <p>In that situation {@code doGetTransaction} hands {@code doBeginNative} a
 * {@code MongoSessionHolder} whose {@code ClientSession} is {@code null} (wrapped from the
 * pre-bound regular {@code SessionHolder}); before the fix the nested begin failed with
 * {@code TransactionSystemException: "ClientSession not available in transaction object"}.</p>
 *
 * <p>The simple {@code DischargeServiceIntegrationSpec} does NOT catch this because no regular
 * session is bound when a spec calls the service directly — only a real request (OSIV - Open Session in View)
 * binds one.
 * These tests bind one explicitly to mimic that.</p>
 *
 * <p>No {@code @NativeRollback}: we assert real commit/rollback, and we own the session binding.</p>
 */
@Integration
class NativeTxWithPreBoundSessionSpec extends Specification {

    DischargeService dischargeService

    @Autowired
    MongoDatastore mongoDatastore

    void setup() {
        Appointment.collection.drop()
        LabResult.collection.drop()
    }

    void cleanup() {
        Appointment.collection.drop()
        LabResult.collection.drop()
        // Guard against a leaked binding affecting other specs.
        if (TransactionSynchronizationManager.hasResource(mongoDatastore)) {
            TransactionSynchronizationManager.unbindResource(mongoDatastore)
        }
    }

    /** Mimics OpenSessionInViewInterceptor: bind a plain GORM session for the "request". */
    private <T> T withOpenSessionInView(Closure<T> work) {
        Session session = mongoDatastore.connect()
        TransactionSynchronizationManager.bindResource(mongoDatastore, new SessionHolder(session))
        try {
            return work.call()
        } finally {
            TransactionSynchronizationManager.unbindResource(mongoDatastore)
            session.disconnect()
        }
    }

    void "regular @Transactional inside native tx JOINS it even when an OSIV session is bound"() {
        given: "a persisted appointment"
        def apptId = new Appointment(
            patientName: 'Eve OSIV', providerName: 'Dr. Bound',
            department: 'ER', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
        ).save(flush: true, failOnError: true).id

        when: "a native tx invokes a regular @Transactional service, OSIV session bound"
        def result = withOpenSessionInView {
            dischargeService.dischargeWithLabOrder(apptId, 'Eve OSIV', 'CT Scan', 'Dr. Bound')
        }

        then: "no 'ClientSession not available' — both writes commit together on the native tx"
        result.appointment.status == 'COMPLETED'
        result.labResult?.testName == 'CT Scan'
        Appointment.withNewSession { Appointment.get(apptId)?.status } == 'COMPLETED'
        LabResult.withNewSession { LabResult.findByPatientName('Eve OSIV')?.testName } == 'CT Scan'
    }

    void "regular @Transactional failure inside native tx rolls back BOTH, with OSIV session bound"() {
        given:
        def apptId = new Appointment(
            patientName: 'Frank OSIV', providerName: 'Dr. Bound',
            department: 'ER', status: 'CHECKED_IN', scheduledDate: LocalDateTime.now()
        ).save(flush: true, failOnError: true).id

        when: "the native tx fails after the legacy lab write"
        withOpenSessionInView {
            dischargeService.dischargeWithLabOrderAndFail(apptId, 'Frank OSIV', 'MRI', 'Dr. Bound')
        }

        then: "exception propagates and the native appointment update + legacy lab both roll back"
        thrown(RuntimeException)
        Appointment.withNewSession { Appointment.get(apptId)?.status } == 'CHECKED_IN'
        LabResult.withNewSession { LabResult.findByPatientName('Frank OSIV') } == null
    }
}
