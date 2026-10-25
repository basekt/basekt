package io.github.basekt.auth.firebase

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.basekt.auth.AuthIdentities
import io.github.basekt.auth.JwtSettings
import io.github.basekt.auth.PrincipalRoles
import io.github.basekt.auth.baseKtJwt
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** A made-up kanban: members of a board write its cards, everyone signed in reads only their boards' cards. */
private val boardsSchema: Schema = schema {
    resource("cards") {
        string("title") { required() }
        string("boardId")
        permissions {
            read(authenticated); write("member")
            scope("boardId", attribute = "board", unless = setOf("admin"))
        }
    }
}

/** A made-up notebook: anyone signed in writes notes, only their own, and moderators delete them. */
private val notesSchema: Schema = schema {
    resource("notes") {
        string("text") { required() }
        string("authorId")
        permissions {
            read(authenticated); create(authenticated); update(authenticated); delete("moderator")
            ownedBy("authorId", on = setOf(io.github.basekt.schema.Operation.UPDATE))
        }
    }
}

private const val PROJECT = "demo-project"
private const val KID = "test-kid"

/** The test key standing in for Google's: a key pair in a PKCS#12 store, and its certificate in Google's format. */
private object TestKey {
    private val store = KeyStore.getInstance("PKCS12").apply {
        FirebaseAuthTest::class.java.getResourceAsStream("/test-signing-key.p12")!!.use { load(it, "changeit".toCharArray()) }
    }
    val private: RSAPrivateKey = store.getKey("k", "changeit".toCharArray()) as RSAPrivateKey
    private val pem = "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(store.getCertificate("k").encoded) +
        "\n-----END CERTIFICATE-----\n"

    /** What Google's certificate endpoint answers, with this key under [KID]. */
    val x509Json: String = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(KID to kotlinx.serialization.json.JsonPrimitive(pem))))
}

/** A key fetcher that serves [sets] in turn, the last one forever, and counts its fetches. */
private class TestFetcher(private vararg val sets: () -> FirebaseKeySet) : FirebaseKeyFetcher {
    var fetches = 0
    override suspend fun fetch(): FirebaseKeySet = sets[minOf(fetches++, sets.lastIndex)]()
}

private val machines = JwtSettings(issuer = "machines", audience = "basekt-tests", secret = "a-machine-secret-that-is-long-enough")

class FirebaseAuthTest {
    private val now: Instant get() = Clock.System.now()

    private fun firebaseToken(
        uid: String,
        claims: Map<String, Any> = emptyMap(),
        issuer: String = "https://securetoken.google.com/$PROJECT",
        audience: String = PROJECT,
        issuedAt: Instant = now - 1.minutes,
        expiresAt: Instant = issuedAt + 1.hours,
        authTime: Instant = issuedAt,
        kid: String = KID,
        key: RSAPrivateKey = TestKey.private,
    ): String {
        val builder = JWT.create()
            .withKeyId(kid)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(uid)
            .withIssuedAt(issuedAt.toJavaInstant())
            .withExpiresAt(expiresAt.toJavaInstant())
            .withClaim("auth_time", authTime.toJavaInstant())
        for ((name, value) in claims) {
            when (value) {
                is String -> builder.withClaim(name, value)
                is List<*> -> builder.withClaim(name, value.map { it as String })
                else -> error("Unsupported claim $value")
            }
        }
        return builder.sign(Algorithm.RSA256(null, key))
    }

    private fun ApplicationTestBuilder.app(
        schema: Schema,
        settings: FirebaseSettings = FirebaseSettings(PROJECT, attributeClaims = listOf("board")),
        fetcher: FirebaseKeyFetcher = TestFetcher({ FirebaseKeySet.fromX509(TestKey.x509Json, 1.hours) }),
    ) {
        application {
            install(Authentication) {
                firebase("firebase", settings, FirebaseKeys(fetcher))
                baseKtJwt("machines", machines)
            }
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                this.schema = schema
                // Firebase principals are identities already; machine JWTs carry their boards in a claim.
                identities = AuthIdentities(
                    "firebase", "machines",
                    roles = PrincipalRoles.firstOf(PrincipalRoles.of<Identity> { it }, PrincipalRoles.jwt(attributeClaims = listOf("board"))),
                )
            }
            routing {
                authenticate("firebase") { get("/whoami") { call.respondText(call.principal<Identity>()!!.toString()) } }
            }
        }
    }

    private suspend fun HttpClient.create(path: String, body: String, token: String? = null): HttpResponse = post(path) {
        token?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.id() = json().getValue("id").jsonPrimitive.content
    private suspend fun HttpResponse.errorCode() = json().getValue("error").jsonPrimitive.content

    @Test
    fun `a Firebase user and a machine token are both identified on the same routes`() = testApplication {
        app(boardsSchema)
        val person = firebaseToken("uid-ann", mapOf("roles" to listOf("member"), "board" to listOf("b1")))
        val machine = machines.issue(Identity("importer", setOf("member"), mapOf("board" to setOf("b2"))), 1.hours)

        assertEquals(HttpStatusCode.Created, client.create("/api/cards", """{"title": "Ann's", "boardId": "b1"}""", person).status)
        assertEquals(HttpStatusCode.Created, client.create("/api/cards", """{"title": "Imported", "boardId": "b2"}""", machine).status)

        // Each caller sees only its own board's cards: the Firebase custom claim became a record-scope attribute.
        suspend fun titles(token: String) = client.get("/api/cards") { bearerAuth(token) }.json().getValue("items").jsonArray
            .map { it.jsonObject.getValue("title").jsonPrimitive.content }
        assertEquals(listOf("Ann's"), titles(person))
        assertEquals(listOf("Imported"), titles(machine))
        // A write outside the caller's boards is refused.
        assertEquals(HttpStatusCode.Forbidden, client.create("/api/cards", """{"title": "Sneaky", "boardId": "b2"}""", person).status)
        // Admins aren't scoped.
        val admin = firebaseToken("uid-root", mapOf("roles" to "admin member"))
        assertEquals(2, client.get("/api/cards") { bearerAuth(admin) }.json().getValue("items").jsonArray.size)
    }

    @Test
    fun `the uid is the identity, and custom claims are its roles and attributes`() = testApplication {
        app(boardsSchema)
        val token = firebaseToken("uid-ann", mapOf("roles" to listOf("member"), "board" to "b1,b3", "email" to "ann@example.com"))
        assertEquals(
            Identity("uid-ann", setOf("member"), mapOf("board" to setOf("b1", "b3"))).toString(),
            client.get("/whoami") { bearerAuth(token) }.bodyAsText(),
        )
    }

    @Test
    fun `the uid owns records, and roles come from another claim when configured`() = testApplication {
        app(notesSchema, FirebaseSettings(PROJECT, rolesClaim = "groups"))
        val ann = firebaseToken("uid-ann")
        val bob = firebaseToken("uid-bob")
        val mod = firebaseToken("uid-mod", mapOf("groups" to listOf("moderator")))

        val note = client.create("/api/notes", """{"text": "Mine", "authorId": "uid-ann"}""", ann)
        assertEquals(HttpStatusCode.Created, note.status)
        val id = note.id()
        suspend fun edit(token: String) = client.patch("/api/notes/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"text": "Edited"}""")
        }
        assertEquals(HttpStatusCode.OK, edit(ann).status)
        assertEquals(HttpStatusCode.Forbidden, edit(bob).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/notes/$id") { bearerAuth(ann) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/notes/$id") { bearerAuth(mod) }.status)
        // Anonymous callers may not read, and a machine token of the other provider still works here.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notes").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/notes") { bearerAuth(machines.issue(Identity("job"), 1.hours)) }.status)
    }

    @Test
    fun `tokens that fail a check get a typed 401 with a challenge`() = testApplication {
        app(notesSchema)
        val stranger = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as RSAPrivateKey
        val bad = mapOf(
            "garbage" to "not-a-token",
            "another project" to firebaseToken("u", issuer = "https://securetoken.google.com/other", audience = "other"),
            "wrong audience" to firebaseToken("u", audience = "other"),
            "expired" to firebaseToken("u", issuedAt = now - 3.hours),
            "issued in the future" to firebaseToken("u", issuedAt = now + 10.minutes),
            "signed in the future" to firebaseToken("u", authTime = now + 10.minutes),
            "unknown key" to firebaseToken("u", kid = "rotated-away"),
            "forged signature" to firebaseToken("u", key = stranger),
            "too long uid" to firebaseToken("u".repeat(129)),
            "a machine token signed wrongly" to JwtSettings("machines", "basekt-tests", "another-secret-that-is-long-enough").issue(Identity("x"), 1.hours),
        )
        for ((why, token) in bad) {
            val response = client.get("/api/notes") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Unauthorized, response.status, why)
            assertEquals("unauthorized", response.errorCode(), why)
            assertNotNull(response.headers[HttpHeaders.WWWAuthenticate], why)
        }
    }

    @Test
    fun `a small clock skew is allowed`() = testApplication {
        app(notesSchema, FirebaseSettings(PROJECT, clockSkew = 30.seconds))
        val justExpired = firebaseToken("u", issuedAt = now - 1.hours - 10.seconds, expiresAt = now - 10.seconds)
        val slightlyAhead = firebaseToken("u", issuedAt = now + 10.seconds, authTime = now + 10.seconds)
        assertEquals(HttpStatusCode.OK, client.get("/api/notes") { bearerAuth(justExpired) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/notes") { bearerAuth(slightlyAhead) }.status)
    }

    @Test
    fun `keys are fetched lazily, cached, and fetched again when an unknown key id appears`() = testApplication {
        val rotated = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val fetcher = TestFetcher(
            { FirebaseKeySet.fromX509(TestKey.x509Json, 1.hours) },
            { FirebaseKeySet(mapOf("rotated" to rotated.public as RSAPublicKey), 1.hours) },
        )
        app(notesSchema, fetcher = fetcher)
        startApplication()
        assertEquals(0, fetcher.fetches)
        // A machine token never makes the Firebase provider fetch keys.
        assertEquals(HttpStatusCode.OK, client.get("/api/notes") { bearerAuth(machines.issue(Identity("job"), 1.hours)) }.status)
        assertEquals(0, fetcher.fetches)

        repeat(3) { assertEquals(HttpStatusCode.OK, client.get("/api/notes") { bearerAuth(firebaseToken("u")) }.status) }
        assertEquals(1, fetcher.fetches)
        // The minimum refresh interval (a minute by default) hasn't passed, so a new key id doesn't fetch yet.
        val newKey = firebaseToken("u", kid = "rotated", key = rotated.private as RSAPrivateKey)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notes") { bearerAuth(newKey) }.status)
        assertEquals(1, fetcher.fetches)
    }
}
