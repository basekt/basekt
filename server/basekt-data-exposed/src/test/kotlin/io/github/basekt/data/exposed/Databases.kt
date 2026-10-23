package io.github.basekt.data.exposed

import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.util.Collections
import java.util.UUID

/** A fresh, empty in-memory H2 database. */
internal fun h2(): Database = Database.connect(h2Url(), driver = "org.h2.Driver")

private fun h2Url() = "jdbc:h2:mem:basekt-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

/** A fresh in-memory H2 database that records the SQL of every statement it prepares. */
internal class RecordingH2 {
    /** The SQL prepared so far. */
    val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val url = h2Url()

    /** The database. */
    val database: Database = Database.connect(getNewConnection = { recording(DriverManager.getConnection(url)) })

    /** Returns the statements prepared while [block] ran. */
    inline fun <T> during(block: () -> T): Pair<T, List<String>> {
        val before = statements.size
        val result = block()
        return result to statements.toList().drop(before)
    }

    private fun recording(connection: Connection): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") statements += args[0] as String
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
}

/**
 * Whether the Postgres tests run: with a database named by `basekt.postgres.url`, or else with a container when
 * Docker is available. With `basekt.postgres.required`, as in CI, they always run, and fail without Docker.
 */
internal fun postgresAvailable(): Boolean =
    externalPostgresUrl != null || postgresRequired || DockerClientFactory.instance().isDockerAvailable

/**
 * The Postgres database of the tests: the one the `basekt.postgres.*` system properties name, which Gradle passes on
 * when set, or else a throwaway container started once for the whole run.
 */
internal fun postgres(): Database = postgresDatabase

private val externalPostgresUrl: String? = System.getProperty("basekt.postgres.url")?.takeIf { it.isNotBlank() }
private val postgresRequired: Boolean = System.getProperty("basekt.postgres.required").toBoolean()

private val postgresDatabase: Database by lazy {
    if (externalPostgresUrl != null) {
        Database.connect(
            url = externalPostgresUrl,
            driver = "org.postgresql.Driver",
            user = System.getProperty("basekt.postgres.user").orEmpty(),
            password = System.getProperty("basekt.postgres.password").orEmpty(),
        )
    } else {
        // Testcontainers' reaper removes the container when the test JVM exits.
        val container = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
        Database.connect(container.jdbcUrl, driver = "org.postgresql.Driver", user = container.username, password = container.password)
    }
}
