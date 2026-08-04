package example

import com.mongodb.client.ClientSession
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Updates
import grails.testing.mixin.integration.Integration
import org.bson.types.ObjectId
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Specification

/**
 * End-to-end tests for the native-transaction retry-on-{@code TransientTransactionError} behaviour.
 *
 * <p>A real MongoDB {@code WriteConflict} (error 112, {@code TransientTransactionError}) is induced
 * deterministically: a competing {@link ClientSession} holds an <em>uncommitted</em> write intent
 * on a document, then the tested closure writes the same document and aborts with a conflict. The
 * competing intent is released from <em>inside</em> the tested closure at the start of a chosen
 * retry attempt, so recovery is exercised with no timing dependency (no sleeps, no background
 * threads racing the retry backoff).</p>
 *
 * <p>No {@code @NativeRollback}: these assert real commit/abort, and we own the competing session
 * and clean up manually.</p>
 */
@Integration
class NativeTransactionRetrySpec extends Specification {

    @Autowired
    MongoDatastore mongoDatastore

    def cleanup() {
        Provider.collection.drop()
    }

    /** Seeds a provider (age = 1) and returns its id. */
    private ObjectId seedProvider() {
        return (Provider.withNativeTransaction {
            new Provider(firstName: 'Retry', lastName: 'Seed', age: 1).save(failOnError: true)
        }).id
    }

    /** Opens a competing session holding an uncommitted write intent on the given provider. */
    private ClientSession openCompetingWriteIntent(ObjectId id) {
        ClientSession competing = mongoDatastore.mongoClient.startSession()
        competing.startTransaction()
        Provider.collection.updateOne(competing, Filters.eq('_id', id), Updates.set('age', 999))
        return competing
    }

    private static void release(ClientSession competing) {
        if (competing.hasActiveTransaction()) {
            competing.abortTransaction()
        }
        competing.close()
    }

    void "no-arg withNativeTransaction does NOT retry: single attempt, WriteConflict propagates"() {
        given:
        ObjectId id = seedProvider()
        ClientSession competing = openCompetingWriteIntent(id)
        int attempts = 0

        when: "the owning tx conflicts with the still-open competing write intent"
        Provider.withNativeTransaction { session ->
            attempts++
            def p = Provider.get(id)
            p.age = 42
            p.save(failOnError: true)
        }

        then: "it fails on the first and only attempt; nothing is persisted"
        thrown(Exception)
        attempts == 1
        Provider.withNewSession { Provider.get(id).age } == 1

        cleanup:
        release(competing)
    }

    void "opt-in retry recovers once the conflicting write intent is released"() {
        given:
        ObjectId id = seedProvider()
        ClientSession competing = openCompetingWriteIntent(id)
        int attempts = 0

        when: "retry is enabled and the competing intent is released at the start of attempt 2"
        def result = Provider.withNativeTransaction(maxRetries: 5, baseBackoffMs: 10, maxBackoffMs: 40) { session ->
            attempts++
            if (attempts >= 2 && competing.hasActiveTransaction()) {
                competing.abortTransaction()   // release the write intent before this attempt's save
            }
            def p = Provider.get(id)
            p.age = 42
            p.save(failOnError: true)
            return p
        }

        then: "the first attempt conflicted, the retry succeeded, and the write is persisted"
        attempts == 2
        result.age == 42
        Provider.withNewSession { Provider.get(id).age } == 42

        cleanup:
        release(competing)
    }

    void "opt-in retry recovers a REUSED pre-fetched instance without re-fetching inside the closure"() {
        given: "an instance fetched ONCE up front and reused on every attempt (mirrors app closures that persist a managed/detached instance rather than re-reading it)"
        ObjectId id = seedProvider()
        Provider prefetched = Provider.withNewSession { Provider.get(id) }
        Long versionBefore = prefetched.version
        ClientSession competing = openCompetingWriteIntent(id)
        int attempts = 0

        when: "the SAME instance is re-saved each attempt; the intent is released at the start of attempt 2"
        def result = Provider.withNativeTransaction(maxRetries: 5, baseBackoffMs: 10, maxBackoffMs: 40) { session ->
            attempts++
            if (attempts >= 2 && competing.hasActiveTransaction()) {
                competing.abortTransaction()
            }
            prefetched.age = 42
            prefetched.save(failOnError: true)
            return prefetched
        }

        then: "attempt 1 conflicted (encodeUpdate had bumped the in-memory version); the abort restored that version so the retry's versioned update matched and committed — without the restore this would fail with OptimisticLockingException on attempt 2"
        attempts == 2
        result.age == 42
        Provider.withNewSession { Provider.get(id).age } == 42
        Provider.withNewSession { Provider.get(id).version } == versionBefore + 1

        cleanup:
        release(competing)
    }

    void "opt-in retry gives up after maxRetries and rethrows the conflict"() {
        given: "a competing intent that is never released, so every attempt conflicts"
        ObjectId id = seedProvider()
        ClientSession competing = openCompetingWriteIntent(id)
        int attempts = 0

        when:
        Provider.withNativeTransaction(maxRetries: 2, baseBackoffMs: 10, maxBackoffMs: 40) { session ->
            attempts++
            def p = Provider.get(id)
            p.age = 42
            p.save(failOnError: true)
        }

        then: "maxRetries + 1 attempts were made, then the conflict propagated; nothing persisted"
        thrown(Exception)
        attempts == 3
        Provider.withNewSession { Provider.get(id).age } == 1

        cleanup:
        release(competing)
    }

    void "explicit maxRetries: 0 disables retry (single attempt)"() {
        given:
        ObjectId id = seedProvider()
        ClientSession competing = openCompetingWriteIntent(id)
        int attempts = 0

        when:
        Provider.withNativeTransaction(maxRetries: 0) { session ->
            attempts++
            def p = Provider.get(id)
            p.age = 42
            p.save(failOnError: true)
        }

        then:
        thrown(Exception)
        attempts == 1

        cleanup:
        release(competing)
    }
}
