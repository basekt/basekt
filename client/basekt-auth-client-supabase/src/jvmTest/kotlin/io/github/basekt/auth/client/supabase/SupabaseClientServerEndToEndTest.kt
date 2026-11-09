package io.github.basekt.auth.client.supabase

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.basekt.auth.AuthIdentities
import io.github.basekt.auth.client.AuthorizedCode
import io.github.basekt.auth.client.OAuthProviders
import io.github.basekt.auth.client.OAuthRedirectFlow
import io.github.basekt.auth.client.PkceChallenge
import io.github.basekt.auth.client.SessionManager
import io.github.basekt.auth.supabase.SupabaseSettings
import io.github.basekt.auth.supabase.supabase
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaInstant

private const val SUPABASE_URL = "https://greenhouse.supabase.co"
private const val ANON_KEY = "supabase-anon-test"
private const val JWT_SECRET = "supabase-shared-hmac-secret-at-least-256-bits!!"

/** First made-up schema: a botanical greenhouse sensor log where botanists record readings for their assigned wing. */
private val greenhouseSchema: Schema = schema {
    resource("readings") {
        string("species") { required() }
        string("wing") { required() }
        permissions {
            read(authenticated)
            create("botanist")
            scope("wing", attribute = "wing")
        }
    }
}

/** Second made-up schema: a music rehearsal studio booking board where members create and edit only their own bookings. */
private val studioSchema: Schema = schema {
    resource("bookings") {
        string("room") { required() }
        string("musicianId")
        permissions {
            read(authenticated)
            create(authenticated)
        }
    }
}

class SupabaseClientServerEndToEndTest {

    private fun mintGoTrueJwt(sub: String, email: String, roles: List<String>, wing: String? = null): String {
        val now = Clock.System.now()
        return JWT.create()
            .withIssuer("$SUPABASE_URL/auth/v1")
            .withSubject(sub)
            .withClaim("email", email)
            .withClaim("role", "authenticated")
            .withClaim("roles", roles)
            .apply { if (wing != null) withClaim("wing", wing) }
            .withIssuedAt((now - 1.minutes).toJavaInstant())
            .withExpiresAt((now + 1.hours).toJavaInstant())
            .sign(Algorithm.HMAC256(JWT_SECRET))
    }

    @Test
    fun `SupabaseAuthClient password and OAuth PKCE sessions authorize BaseKt routes across two schemas`() {
        val goTrueMock = MockEngine { request ->
            val grant = request.url.parameters["grant_type"]
            val body = when (grant) {
                "password" -> {
                    val token = mintGoTrueJwt("botanist-1", "fern@greenhouse.example", listOf("botanist"), wing = "orchid")
                    """{"access_token":"$token","refresh_token":"ref-botanist","expires_in":3600,"user":{"id":"botanist-1","email":"fern@greenhouse.example"}}"""
                }
                "pkce" -> {
                    val token = mintGoTrueJwt("musician-7", "cello@studio.example", emptyList())
                    """{"access_token":"$token","refresh_token":"ref-musician","expires_in":3600,"user":{"id":"musician-7","email":"cello@studio.example"}}"""
                }
                else -> error("Unexpected GoTrue grant: $grant")
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val authClient = SupabaseAuthClient(
            settings = SupabaseClientSettings(url = SUPABASE_URL, anonKey = ANON_KEY),
            httpClient = HttpClient(goTrueMock),
            oauthFlow = object : OAuthRedirectFlow {
                override suspend fun authorize(
                    provider: String,
                    rememberSession: Boolean,
                    buildAuthorizeUrl: (redirectUri: String, challenge: PkceChallenge) -> String,
                ): AuthorizedCode {
                    val challenge = PkceChallenge.generate()
                    buildAuthorizeUrl("http://127.0.0.1:40000/callback", challenge)
                    return AuthorizedCode("oauth-code-1", challenge.verifier)
                }
            },
        )
        val sessions = SessionManager(authClient)

        // Schema 1: Greenhouse readings with role + record-scope attributes verified by server:basekt-auth-supabase.
        testApplication {
            application {
                install(Authentication) {
                    supabase(
                        "supabase",
                        SupabaseSettings(
                            url = SUPABASE_URL,
                            anonKey = ANON_KEY,
                            jwtSecret = JWT_SECRET,
                            attributeClaims = listOf("wing"),
                        ),
                    )
                }
                install(BaseKt) {
                    schema = greenhouseSchema
                    dataSource = InMemoryDataSource()
                    identities = AuthIdentities("supabase")
                }
            }

            sessions.signIn("fern@greenhouse.example", "correct-password")
            val createAllowed = client.post("/api/readings") {
                bearerAuth(sessions.freshAccessToken())
                contentType(ContentType.Application.Json)
                setBody("""{"species":"Phalaenopsis","wing":"orchid"}""")
            }
            assertEquals(HttpStatusCode.Created, createAllowed.status)

            val outOfScope = client.post("/api/readings") {
                bearerAuth(sessions.freshAccessToken())
                contentType(ContentType.Application.Json)
                setBody("""{"species":"Cactus","wing":"arid"}""")
            }
            assertEquals(HttpStatusCode.Forbidden, outOfScope.status)
        }

        // Schema 2: Rehearsal studio bookings signed in via Google OAuth PKCE.
        testApplication {
            application {
                install(Authentication) {
                    supabase(
                        "supabase",
                        SupabaseSettings(url = SUPABASE_URL, anonKey = ANON_KEY, jwtSecret = JWT_SECRET),
                    )
                }
                install(BaseKt) {
                    schema = studioSchema
                    dataSource = InMemoryDataSource()
                    identities = AuthIdentities("supabase")
                }
            }

            val oauthSession = sessions.signInWithOAuth(OAuthProviders.GOOGLE, remember = true)
            assertEquals("musician-7", oauthSession.subject)
            val created = client.post("/api/bookings") {
                bearerAuth(sessions.freshAccessToken())
                contentType(ContentType.Application.Json)
                setBody("""{"room":"Studio B","musicianId":"${oauthSession.subject}"}""")
            }
            assertEquals(HttpStatusCode.Created, created.status)

            val listed = client.get("/api/bookings") {
                bearerAuth(sessions.freshAccessToken())
            }
            assertEquals(HttpStatusCode.OK, listed.status)
        }
    }
}
