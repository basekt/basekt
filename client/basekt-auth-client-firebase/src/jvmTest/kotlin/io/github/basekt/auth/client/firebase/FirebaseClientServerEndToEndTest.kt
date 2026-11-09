package io.github.basekt.auth.client.firebase

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.basekt.auth.AuthIdentities
import io.github.basekt.auth.client.SessionManager
import io.github.basekt.auth.firebase.FirebaseKeySet
import io.github.basekt.auth.firebase.FirebaseKeys
import io.github.basekt.auth.firebase.FirebaseSettings
import io.github.basekt.auth.firebase.firebase
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaInstant

private const val FIREBASE_PROJECT = "clinic-firebase-demo"
private const val KEY_ID = "fb-kid-1"

/** First made-up schema: a veterinary clinic schedule where clinicians create visits for their clinic branch. */
private val clinicSchema: Schema = schema {
    resource("visits") {
        string("patientName") { required() }
        string("branch") { required() }
        permissions {
            read(authenticated)
            create("clinician")
            scope("branch", attribute = "branch")
        }
    }
}

/** Second made-up schema: a bicycle repair workshop board where technicians open repair tickets. */
private val workshopSchema: Schema = schema {
    resource("tickets") {
        string("summary") { required() }
        permissions {
            read(authenticated)
            create("technician")
        }
    }
}

class FirebaseClientServerEndToEndTest {

    private val rsaPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = rsaPair.public as RSAPublicKey
    private val privateKey = rsaPair.private as RSAPrivateKey

    private fun mintFirebaseIdToken(uid: String, roles: List<String>, branch: String? = null): String {
        val now = Clock.System.now()
        val issuedAt = (now - 1.minutes).toJavaInstant()
        return JWT.create()
            .withKeyId(KEY_ID)
            .withIssuer("https://securetoken.google.com/$FIREBASE_PROJECT")
            .withAudience(FIREBASE_PROJECT)
            .withSubject(uid)
            .withClaim("auth_time", issuedAt.epochSecond)
            .withClaim("roles", roles)
            .apply { if (branch != null) withClaim("branch", branch) }
            .withIssuedAt(issuedAt)
            .withExpiresAt((now + 1.hours).toJavaInstant())
            .sign(Algorithm.RSA256(publicKey, privateKey))
    }

    @Test
    fun `FirebaseAuthClient signIn and refresh tokens authorize BaseKt routes across two schemas`() {
        var nowSeconds = 1_000L
        val identityMock = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/v1/accounts:signInWithPassword" -> {
                    val token = mintFirebaseIdToken("vet-1", listOf("clinician"), branch = "north")
                    """{"localId":"vet-1","email":"vet@clinic.example","idToken":"$token","refreshToken":"fb-ref-1","expiresIn":"600"}"""
                }
                "/v1/token" -> {
                    val token = mintFirebaseIdToken("tech-9", listOf("technician"))
                    """{"user_id":"tech-9","id_token":"$token","refresh_token":"fb-ref-2","expires_in":"3600"}"""
                }
                else -> error("Unexpected Firebase endpoint: ${request.url.encodedPath}")
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val authClient = FirebaseAuthClient(
            settings = FirebaseClientSettings(apiKey = "test-web-key"),
            httpClient = HttpClient(identityMock),
            nowEpochSeconds = { nowSeconds },
        )
        val sessions = SessionManager(authClient, refreshMarginSeconds = 300L, nowEpochSeconds = { nowSeconds })
        val keys = FirebaseKeys({ FirebaseKeySet(mapOf(KEY_ID to publicKey), 2.hours) })

        // Schema 1: Veterinary clinic visits scoped by branch claim.
        testApplication {
            application {
                install(Authentication) {
                    firebase(
                        "firebase",
                        FirebaseSettings(projectId = FIREBASE_PROJECT, attributeClaims = listOf("branch")),
                        keys,
                    )
                }
                install(BaseKt) {
                    schema = clinicSchema
                    dataSource = InMemoryDataSource()
                    identities = AuthIdentities("firebase")
                }
            }

            sessions.signIn("vet@clinic.example", "correct-password")
            val created = client.post("/api/visits") {
                bearerAuth(sessions.freshAccessToken())
                contentType(ContentType.Application.Json)
                setBody("""{"patientName":"Mochi","branch":"north"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)

            val forbiddenBranch = client.post("/api/visits") {
                bearerAuth(sessions.freshAccessToken())
                contentType(ContentType.Application.Json)
                setBody("""{"patientName":"Mochi","branch":"south"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, forbiddenBranch.status)
        }

        // Schema 2: Advance clock into refresh window and verify Secure Token refresh against workshop schema.
        nowSeconds = 1_400L
        testApplication {
            application {
                install(Authentication) {
                    firebase("firebase", FirebaseSettings(projectId = FIREBASE_PROJECT), keys)
                }
                install(BaseKt) {
                    schema = workshopSchema
                    dataSource = InMemoryDataSource()
                    identities = AuthIdentities("firebase")
                }
            }

            val created = client.post("/api/tickets") {
                bearerAuth(sessions.freshAccessToken())
                contentType(ContentType.Application.Json)
                setBody("""{"summary":"True rear wheel"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)
        }
    }
}
