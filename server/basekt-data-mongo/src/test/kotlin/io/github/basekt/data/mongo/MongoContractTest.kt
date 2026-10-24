package io.github.basekt.data.mongo

import io.github.basekt.data.DataSource
import io.github.basekt.testing.DataSourceContract
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The contract on a real MongoDB: the server `-Pbasekt.mongo.url=mongodb://host/?replicaSet=rs0` names, or a container
 * when Docker is available, or with `-Pbasekt.mongo.embedded=true` an embedded one. Skipped without any, except with
 * `-Pbasekt.mongo.required=true`, as in CI.
 */
class MongoContractTest : DataSourceContract() {
    private val databases = mutableListOf<String>()

    override fun createDataSource(): DataSource {
        assumeTrue(mongoAvailable(), "No MongoDB: pass -Pbasekt.mongo.url, start Docker, or pass -Pbasekt.mongo.embedded=true.")
        // Each test gets its own database, so tests start empty and can't see each other's records.
        val name = freshDatabaseName().also { databases += it }
        return MongoDataSource(mongoClient, name, contractSchema).also { runBlocking { it.migrate() } }
    }

    @AfterEach
    fun dropDatabases(): Unit = runBlocking {
        if (databases.isNotEmpty()) databases.forEach { mongoClient.getDatabase(it).drop() }
        databases.clear()
    }
}
