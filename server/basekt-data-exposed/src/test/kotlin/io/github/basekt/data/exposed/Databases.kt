package io.github.basekt.data.exposed

import org.jetbrains.exposed.v1.jdbc.Database
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

/** The Postgres database named by the `basekt.postgres.*` system properties, which Gradle passes on when set. */
internal fun postgres(): Database = Database.connect(
    url = System.getProperty("basekt.postgres.url"),
    driver = "org.postgresql.Driver",
    user = System.getProperty("basekt.postgres.user").orEmpty(),
    password = System.getProperty("basekt.postgres.password").orEmpty(),
)
