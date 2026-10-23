package io.github.basekt.data.exposed

import io.github.basekt.data.DataSource
import io.github.basekt.testing.DataSourceContract
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.condition.EnabledIf
import java.util.concurrent.atomic.AtomicInteger

class H2ContractTest : DataSourceContract() {
    override fun createDataSource(): DataSource =
        ExposedDataSource(h2(), contractSchema).also { runBlocking { it.createTables() } }
}

/**
 * The contract on a real Postgres: a container when Docker is available, or the database that
 * `-Pbasekt.postgres.url=jdbc:postgresql://host/db` (and its user and password) names. Skipped without either, except
 * with `-Pbasekt.postgres.required=true`, as in CI.
 */
@EnabledIf("io.github.basekt.data.exposed.DatabasesKt#postgresAvailable")
class PostgresContractTest : DataSourceContract() {
    private val created = mutableListOf<ExposedDataSource>()

    override fun createDataSource(): DataSource {
        // Each test gets its own tables, so tests start empty and can't see each other's records.
        val mapping = TableMapping(tablePrefix = "contract_${run.incrementAndGet()}_${System.nanoTime()}_")
        return ExposedDataSource(postgres(), contractSchema, mapping).also {
            runBlocking { it.createTables() }
            created += it
        }
    }

    @AfterEach
    fun dropTables(): Unit = runBlocking { created.forEach { it.dropTables() } }

    private companion object {
        val run = AtomicInteger()
    }
}
