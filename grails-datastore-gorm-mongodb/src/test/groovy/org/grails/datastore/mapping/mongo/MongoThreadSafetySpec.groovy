package org.grails.datastore.mapping.mongo

import grails.gorm.annotation.Entity
import grails.gorm.tests.GormDatastoreSpec
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MongoThreadSafetySpec extends GormDatastoreSpec {

    @Override
    List getDomainClasses() {
        [ThreadTestEntity]
    }

    def "test concurrent native transaction creation"() {
        given:
        def threadCount = 10
        def executor = Executors.newFixedThreadPool(threadCount)
        def latch = new CountDownLatch(threadCount)
        def successCount = new AtomicInteger(0)
        def errorCount = new AtomicInteger(0)

        when: "multiple threads create native transactions concurrently"
        threadCount.times { i ->
            executor.submit {
                try {
                    ThreadTestEntity.withNativeTransaction { session ->
                        new ThreadTestEntity(name: "Entity-${i}").save(flush: true)
                        successCount.incrementAndGet()
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        then:
        successCount.get() == threadCount
        errorCount.get() == 0
        ThreadTestEntity.count() == threadCount
    }

    def "test nested transactions across threads"() {
        given:
        def threadCount = 5
        def executor = Executors.newFixedThreadPool(threadCount)
        def latch = new CountDownLatch(threadCount)
        def results = Collections.synchronizedList([])

        when: "nested transactions run concurrently"
        threadCount.times { i ->
            executor.submit {
                try {
                    def result = ThreadTestEntity.withNativeTransaction { outerSession ->
                        new ThreadTestEntity(name: "Outer-${i}").save(flush: true)
                        
                        ThreadTestEntity.withNativeTransaction { innerSession ->
                            new ThreadTestEntity(name: "Inner-${i}").save(flush: true)
                            return [
                                outerSessionId: outerSession.getServerSession().getIdentifier(),
                                innerSessionId: innerSession.getServerSession().getIdentifier(),
                                sameSession: outerSession == innerSession
                            ]
                        }
                    }
                    results.add(result)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        then:
        results.size() == threadCount
        results.every { it.sameSession == true }
        ThreadTestEntity.count() == threadCount * 2
    }

    def "test persister creation thread safety"() {
        given:
        def threadCount = 20
        def executor = Executors.newFixedThreadPool(threadCount)
        def latch = new CountDownLatch(threadCount)
        def persisters = Collections.synchronizedSet(new HashSet())

        when: "multiple threads access persisters concurrently"
        threadCount.times { i ->
            executor.submit {
                try {
                    def session = datastore.connect()
                    def persister = session.createPersister(ThreadTestEntity, session.mappingContext)
                    persisters.add(persister)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        executor.shutdown()

        then:
        persisters.size() == 1 // Should reuse same persister instance
    }
}

@Entity
class ThreadTestEntity {
    String name
}