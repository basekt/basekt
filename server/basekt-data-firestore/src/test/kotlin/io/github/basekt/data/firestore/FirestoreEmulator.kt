package io.github.basekt.data.firestore

import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import org.testcontainers.DockerClientFactory
import org.testcontainers.gcloud.FirestoreEmulatorContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/*
 * The Firestore emulator the tests run against, in the order they look:
 *  1. the one `basekt.firestore.emulator` names, as host:port (`gcloud emulators firestore start` or `firebase emulators:start`);
 *  2. a Testcontainers container of the Cloud SDK's emulator image, when Docker is available.
 * Without one, the tests are skipped, except with `basekt.firestore.required`, as in CI, where they fail.
 */

private val externalEmulator: String? = System.getProperty("basekt.firestore.emulator")?.takeIf { it.isNotBlank() }
private val emulatorRequired: Boolean = System.getProperty("basekt.firestore.required").toBoolean()

/** Whether the Firestore tests run. */
internal fun emulatorAvailable(): Boolean =
    externalEmulator != null || emulatorRequired || runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

/** Why the tests are skipped when they are. */
internal const val NO_EMULATOR = "No Firestore emulator: pass -Pbasekt.firestore.emulator=host:port or start Docker."

/** The emulator's host:port. Testcontainers' reaper removes the container when the test JVM exits. */
private val emulatorHost: String by lazy {
    externalEmulator ?: FirestoreEmulatorContainer(DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators"))
        .also { it.start() }
        .emulatorEndpoint
}

/** A client of the emulator in a project no other test uses, so each test starts with an empty database. */
internal fun freshFirestore(): Firestore = FirestoreOptions.newBuilder()
    .setProjectId("basekt-${UUID.randomUUID().toString().take(8)}")
    // The emulator host brings its own credentials; NoCredentials would turn TLS back on.
    .setEmulatorHost(emulatorHost)
    .build()
    .service
