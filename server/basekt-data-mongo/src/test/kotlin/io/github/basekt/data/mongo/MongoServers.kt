package io.github.basekt.data.mongo

import com.mongodb.kotlin.client.coroutine.MongoClient
import de.flapdoodle.embed.mongo.commands.MongodArguments
import de.flapdoodle.embed.mongo.config.Storage
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.transitions.Mongod
import de.flapdoodle.commons.reverse.transitions.Start
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.testcontainers.DockerClientFactory
import org.testcontainers.mongodb.MongoDBContainer
import java.util.UUID

/*
 * The MongoDB the tests run against, in the order they look:
 *  1. the server `basekt.mongo.url` names (a replica set, for transactions);
 *  2. a Testcontainers container, when Docker is available;
 *  3. with `basekt.mongo.embedded`, an embedded mongod run as a single-node replica set, downloaded once to ~/.embedmongo.
 * Without one, the tests are skipped, except with `basekt.mongo.required`, as in CI, where they fail.
 */

private val externalUrl: String? = System.getProperty("basekt.mongo.url")?.takeIf { it.isNotBlank() }
private val embeddedWanted: Boolean = System.getProperty("basekt.mongo.embedded").toBoolean()
internal val mongoRequired: Boolean = System.getProperty("basekt.mongo.required").toBoolean()

/** Whether the Mongo tests run. */
internal fun mongoAvailable(): Boolean =
    externalUrl != null || mongoRequired || embeddedWanted || runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

private const val REPLICA_SET = "rs0"

private val connectionString: String by lazy {
    when {
        externalUrl != null -> externalUrl
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false) -> {
            // Testcontainers' reaper removes the container when the test JVM exits. The image starts as a replica set.
            val container = MongoDBContainer("mongo:8.0").also { it.start() }
            // The replica set names the container by its own hostname, which the test JVM can't reach, so the client is
            // told to talk to the mapped port directly instead of discovering the members. A direct connection to a
            // replica set member still supports transactions.
            "mongodb://${container.host}:${container.firstMappedPort}/?directConnection=true"
        }
        else -> embeddedReplicaSet()
    }
}

/** An embedded mongod as a single-node replica set, as GoLearn's own tests run it. */
private fun embeddedReplicaSet(): String {
    val running = Mongod.builder()
        .mongodArguments(Start.to(MongodArguments::class.java).initializedWith(MongodArguments.defaults().withReplication(Storage.of(REPLICA_SET, 0))))
        .build()
        .start(Version.Main.V8_0)
    Runtime.getRuntime().addShutdownHook(Thread { running.close() })
    val address = running.current().serverAddress
    val hostAndPort = "${address.host}:${address.port}"
    runBlocking {
        val direct = MongoClient.create("mongodb://$hostAndPort/?directConnection=true")
        try {
            val admin = direct.getDatabase("admin")
            admin.runCommand(Document("replSetInitiate", Document("_id", REPLICA_SET).append("members", listOf(Document("_id", 0).append("host", hostAndPort)))))
            repeat(100) {
                if (admin.runCommand(Document("hello", 1)).getBoolean("isWritablePrimary") == true) return@runBlocking
                delay(100)
            }
            error("the embedded mongod did not become primary")
        } finally {
            direct.close()
        }
    }
    return "mongodb://$hostAndPort/?replicaSet=$REPLICA_SET"
}

/** The client of the tests' MongoDB, shared by the whole run. */
internal val mongoClient: MongoClient by lazy { MongoClient.create(connectionString) }

/** A database name no other test uses, so each test starts empty. */
internal fun freshDatabaseName(): String = "basekt_${UUID.randomUUID().toString().take(8)}"
