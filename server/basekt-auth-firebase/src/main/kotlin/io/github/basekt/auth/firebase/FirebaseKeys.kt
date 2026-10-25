package io.github.basekt.auth.firebase

import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/**
 * Public keys that sign ID tokens, by key id, and how long they may be cached.
 *
 * @property keys the RSA public keys, by the `kid` that names them in a token's header.
 * @property maxAge how long the keys may be used before fetching them again, from the response's `Cache-Control`.
 */
public class FirebaseKeySet(
    public val keys: Map<String, RSAPublicKey>,
    public val maxAge: Duration,
) {
    init {
        require(maxAge >= Duration.ZERO) { "A key set's max age can't be negative, not $maxAge." }
    }

    /** Parses key sets. */
    public companion object {
        /**
         * Parses the JSON object Google serves for Firebase: each key id mapped to a PEM X.509 certificate whose public
         * key is an RSA key.
         */
        public fun fromX509(json: String, maxAge: Duration): FirebaseKeySet {
            val certificates = CertificateFactory.getInstance("X.509")
            val keys = Json.parseToJsonElement(json).jsonObject.mapValues { (kid, pem) ->
                val certificate = certificates.generateCertificate(pem.jsonPrimitive.content.byteInputStream())
                requireNotNull(certificate.publicKey as? RSAPublicKey) { "The certificate of key $kid doesn't hold an RSA key." }
            }
            return FirebaseKeySet(keys, maxAge)
        }
    }
}

/**
 * Fetches the public keys that sign Firebase ID tokens. Tests pass one that returns a test key; servers use [google].
 */
public fun interface FirebaseKeyFetcher {
    /** Fetches the current keys. Throws if they can't be fetched. */
    public suspend fun fetch(): FirebaseKeySet

    /** The fetcher of Google's keys. */
    public companion object {
        /** Where Google publishes the X.509 certificates of the keys that sign Firebase ID tokens. */
        public const val GOOGLE_X509_URL: String =
            "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"

        /**
         * Fetches the certificates at [url] over HTTPS and caches them for the response's `Cache-Control: max-age`.
         * Nothing is fetched until the first token is checked, and each request gives up after [timeout].
         */
        public fun google(url: String = GOOGLE_X509_URL, timeout: Duration = 10.seconds): FirebaseKeyFetcher =
            HttpKeyFetcher(URI.create(url), timeout)
    }
}

/** Fetches X.509 certificates with the JDK's HTTP client, made on the first fetch. */
private class HttpKeyFetcher(private val uri: URI, private val timeout: Duration) : FirebaseKeyFetcher {
    private val client by lazy { HttpClient.newBuilder().connectTimeout(timeout.toJavaDuration()).build() }

    override suspend fun fetch(): FirebaseKeySet {
        val request = HttpRequest.newBuilder(uri).timeout(timeout.toJavaDuration()).GET().build()
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() != 200) throw IOException("Fetching Firebase keys from $uri answered ${response.statusCode()}.")
        return FirebaseKeySet.fromX509(response.body(), maxAgeOf(response.headers().firstValue("Cache-Control").orElse(null)))
    }
}

/** Reads `max-age` from a `Cache-Control` header. No header, `no-cache`, `no-store` or no `max-age` is zero. */
internal fun maxAgeOf(cacheControl: String?): Duration {
    val directives = cacheControl?.split(',')?.map { it.trim().lowercase() } ?: return Duration.ZERO
    if ("no-cache" in directives || "no-store" in directives) return Duration.ZERO
    val seconds = directives.firstOrNull { it.startsWith("max-age=") }?.substringAfter('=')
    return seconds?.trim('"')?.toLongOrNull()?.takeIf { it >= 0 }?.seconds ?: Duration.ZERO
}

/**
 * A cache of the keys that sign Firebase ID tokens, refreshed through [fetcher] when they expire.
 *
 * - Keys are kept for the fetched set's `maxAge`, and at least [minRefreshInterval].
 * - A token with a key id the cache doesn't know fetches again, at most once per [minRefreshInterval], since Google
 *   may have rotated its keys.
 * - When a refresh fails, the keys already held are kept and tried again after [minRefreshInterval]. With no keys
 *   held, the error reaches the caller, and the token is refused.
 *
 * Make one per provider: it holds the fetched keys, and fetches nothing when it's made.
 */
public class FirebaseKeys(
    private val fetcher: FirebaseKeyFetcher = FirebaseKeyFetcher.google(),
    private val minRefreshInterval: Duration = 1.minutes,
    private val clock: Clock = Clock.System,
) {
    init {
        require(minRefreshInterval >= Duration.ZERO) { "The minimum refresh interval can't be negative." }
    }

    private val lock = Mutex()

    @Volatile private var current: FirebaseKeySet? = null

    @Volatile private var expiresAt: Instant = Instant.DISTANT_PAST

    @Volatile private var lastAttempt: Instant = Instant.DISTANT_PAST

    /** Returns the key called [kid], fetching keys when the cache is empty, expired or doesn't know it. */
    public suspend fun key(kid: String): RSAPublicKey? {
        current?.keys?.get(kid)?.let { if (clock.now() < expiresAt) return it }
        return lock.withLock {
            val now = clock.now()
            val held = current
            val wanted = held == null || now >= expiresAt || kid !in held.keys
            if (wanted && (held == null || now >= lastAttempt + minRefreshInterval)) refresh(now, held)
            current?.keys?.get(kid)
        }
    }

    private suspend fun refresh(now: Instant, held: FirebaseKeySet?) {
        lastAttempt = now
        try {
            val fetched = fetcher.fetch()
            current = fetched
            expiresAt = now + maxOf(fetched.maxAge, minRefreshInterval)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            if (held == null) throw cause
        }
    }
}
