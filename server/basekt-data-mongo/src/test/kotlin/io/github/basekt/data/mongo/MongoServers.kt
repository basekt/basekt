package io.github.basekt.data.mongo

import com.mongodb.MongoCommandException
import com.mongodb.kotlin.client.coroutine.MongoClient
import de.flapdoodle.commons.reverse.transitions.Start
import de.flapdoodle.embed.mongo.commands.MongodArguments
import de.flapdoodle.embed.mongo.config.Storage
import de.flapdoodle.embed.mongo.distribution.Version
import de.flapdoodle.embed.mongo.transitions.Mongod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID

/*
 * The MongoDB the tests run against, in the order they look:
 *  1. the server `basekt.mongo.url` names (a replica set, for transactions);
 *  2. a Testcontainers container of the official image, when Docker is available (falling back to embedded mongod if the
 *     container cannot start on the host);
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
private const val CLIENT_TIMEOUT_OPTIONS = "serverSelectionTimeoutMS=5000&connectTimeoutMS=5000&socketTimeoutMS=15000"

private fun resolveConnectionString(): String = when {
    externalUrl != null -> externalUrl
    embeddedWanted -> embeddedReplicaSet()
    runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false) -> {
        try {
            containerReplicaSet()
        } catch (containerError: Throwable) {
            System.err.println("MongoDB container failed to start (${containerError.message}); falling back to embedded mongod.")
            try {
                embeddedReplicaSet()
            } catch (embeddedError: Throwable) {
                containerError.addSuppressed(embeddedError)
                throw containerError
            }
        }
    }
    else -> embeddedReplicaSet()
}

/**
 * The official image as a single-node replica set: started with `--replSet`, waiting on TCP port 27017 rather than log
 * output (which hangs for the full wait timeout if the image changes its log format or exits early), then initiated over
 * BSON. The tests reach it by its mapped IPv4 port with a direct connection, so the member's internal hostname never
 * matters. Testcontainers' reaper removes the container when the test JVM exits.
 */
private fun containerReplicaSet(): String {
    val container = GenericContainer(DockerImageName.parse("mongo:8.0"))
        .withExposedPorts(27017)
        .withCommand("--replSet", REPLICA_SET, "--bind_ip_all")
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(30)))
    try {
        container.start()
        val host = container.host.let { if (it == "localhost") "127.0.0.1" else it }
        val port = container.getMappedPort(27017)
        val url = "mongodb://$host:$port/?directConnection=true&$CLIENT_TIMEOUT_OPTIONS"
        initiateAndAwaitPrimary(url, "localhost:27017")
        return url
    } catch (e: Throwable) {
        runCatching { container.stop() }
        throw e
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
    initiateAndAwaitPrimary("mongodb://$hostAndPort/?directConnection=true&$CLIENT_TIMEOUT_OPTIONS", hostAndPort)
    return "mongodb://$hostAndPort/?replicaSet=$REPLICA_SET&$CLIENT_TIMEOUT_OPTIONS"
}

/** Initiates [REPLICA_SET] on [url] and waits until the node elects itself primary, which transactions need. */
private fun initiateAndAwaitPrimary(url: String, memberHost: String) = runBlocking {
    val direct = MongoClient.create(url)
    try {
        val admin = direct.getDatabase("admin")
        var initiated = false
        var lastError: Throwable? = null
        for (attempt in 1..75) {
            try {
                admin.runCommand(
                    Document(
                        "replSetInitiate",
                        Document("_id", REPLICA_SET).append("members", listOf(Document("_id", 0).append("host", memberHost))),
                    ),
                )
                initiated = true
                break
            } catch (e: MongoCommandException) {
                if (e.errorCode == 23 || "already initialized" in e.errorMessage.lowercase()) {
                    initiated = true
                    break
                }
                lastError = e
            } catch (e: Throwable) {
                lastError = e
            }
            delay(200)
        }
        if (!initiated) throw IllegalStateException("MongoDB replica set initiation failed at $url", lastError)
        for (attempt in 1..100) {
            if (runCatching { admin.runCommand(Document("hello", 1)).getBoolean("isWritablePrimary") }.getOrNull() == true) {
                return@runBlocking
            }
            delay(100)
        }
        error("the MongoDB server at $url did not become primary")
    } finally {
        direct.close()
    }
}

private val resolvedClient: Result<MongoClient> by lazy {
    runCatching { MongoClient.create(resolveConnectionString()) }
}

/** The client of the tests' MongoDB, shared by the whole run. */
internal val mongoClient: MongoClient
    get() = resolvedClient.getOrThrow()

/** A database name no other test uses, so each test starts empty. */
internal fun freshDatabaseName(): String = "basekt_${UUID.randomUUID().toString().take(8)}"
