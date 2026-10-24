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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/*
 * The MongoDB the tests run against, in the order they look:
 *  1. the server `basekt.mongo.url` names (a replica set, for transactions);
 *  2. a Testcontainers container of the official image, when Docker is available;
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
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false) -> containerReplicaSet()
        else -> embeddedReplicaSet()
    }
}

/**
 * The official image as a single-node replica set: started with `--replSet`, then initiated from inside, where the node
 * is `localhost:27017`. The tests reach it by its mapped port with a direct connection, so the member's own name, which
 * the host can't reach, never matters. Testcontainers' reaper removes the container when the test JVM exits.
 */
private fun containerReplicaSet(): String {
    val container = GenericContainer(DockerImageName.parse("mongo:8.0"))
        .withExposedPorts(27017)
        .withCommand("--replSet", REPLICA_SET, "--bind_ip_all")
        .waitingFor(Wait.forLogMessage(".*[Ww]aiting for connections.*", 1))
    container.start()
    val initiated = container.execInContainer(
        "mongosh", "--quiet", "--eval", "rs.initiate({_id: '$REPLICA_SET', members: [{_id: 0, host: 'localhost:27017'}]})",
    )
    check(initiated.exitCode == 0) { "rs.initiate failed: ${initiated.stdout} ${initiated.stderr}" }
    val url = "mongodb://${container.host}:${container.getMappedPort(27017)}/?directConnection=true"
    awaitPrimary(url)
    return url
}

/** Waits until the server at [url] has elected itself, which transactions need. */
private fun awaitPrimary(url: String) = runBlocking {
    val direct = MongoClient.create(url)
    try {
        val admin = direct.getDatabase("admin")
        repeat(300) {
            if (runCatching { admin.runCommand(Document("hello", 1)).getBoolean("isWritablePrimary") }.getOrNull() == true) return@runBlocking
            delay(100)
        }
        error("the MongoDB server did not become primary")
    } finally {
        direct.close()
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
