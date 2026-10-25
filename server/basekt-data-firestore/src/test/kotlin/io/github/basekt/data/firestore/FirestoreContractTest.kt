package io.github.basekt.data.firestore

import com.google.cloud.firestore.Firestore
import io.github.basekt.data.DataSource
import io.github.basekt.testing.DataSourceContract
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The contract on the Firestore emulator: the one `-Pbasekt.firestore.emulator=host:port` names, or a container when
 * Docker is available. Skipped without either, except with `-Pbasekt.firestore.required=true`, as in CI.
 */
class FirestoreContractTest : DataSourceContract() {
    private val clients = mutableListOf<Firestore>()

    override fun createDataSource(): DataSource {
        assumeTrue(emulatorAvailable(), NO_EMULATOR)
        // Each test gets its own project, so tests start empty and can't see each other's records.
        val firestore = freshFirestore().also { clients += it }
        return FirestoreDataSource(firestore, contractSchema)
    }

    @AfterEach
    fun closeClients() {
        clients.forEach { it.close() }
        clients.clear()
    }
}
