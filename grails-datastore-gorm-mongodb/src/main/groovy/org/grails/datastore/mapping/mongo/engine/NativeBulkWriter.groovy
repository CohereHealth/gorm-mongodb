package org.grails.datastore.mapping.mongo.engine

import com.mongodb.client.ClientSession
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.WriteModel
import groovy.transform.CompileStatic

/**
 * Collects {@link WriteModel}s and executes them as a single
 * {@code bulkWrite} call against a MongoDB collection.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * def writer = new NativeBulkWriter(collection, clientSession)
 * writer.add(new InsertOneModel(obj1))
 * writer.add(new InsertOneModel(obj2))
 * writer.add(new UpdateOneModel(query, update))
 * def result = writer.execute()
 * }</pre>
 *
 * @since 6.x
 */
@CompileStatic
class NativeBulkWriter {

    private final MongoCollection collection
    private final ClientSession clientSession
    private final List<WriteModel> writes = []

    NativeBulkWriter(MongoCollection collection, ClientSession clientSession = null) {
        this.collection = collection
        this.clientSession = clientSession
    }

    NativeBulkWriter add(WriteModel writeModel) {
        writes << writeModel
        return this
    }

    boolean hasWrites() {
        return !writes.isEmpty()
    }

    int size() {
        return writes.size()
    }

    /**
     * Executes all collected write models as a single bulkWrite.
     * Passes the ClientSession if one is available.
     */
    void execute() {
        if (writes.isEmpty()) return

        if (clientSession) {
            collection.bulkWrite(clientSession, writes)
        } else {
            collection.bulkWrite(writes)
        }
    }
}
