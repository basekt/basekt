package io.github.basekt.auth.supabase

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.basekt.auth.AuthIdentities
import io.github.basekt.auth.PrincipalRoles
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant

private const val URL = "https://demo.supabase.co"
private const val ANON_KEY = "test-anon-key"
private const val SECRET = "test-hmac-secret-at-least-256-bits-long!!"

private val testSchema: Schema = schema {
    resource("posts") {
        string("title") { required() }
        string("authorId")
        permissions {
            read(authenticated)
            create("writer")
            delete("admin")
        }
    }
}

class SupabaseAuthTest {
    private val now: Instant get() = Clock.System.now()

    private fun supabaseToken(
        uid: String,
        issuer: String = "$URL/auth/v1",
        roles: List<String> = emptyList(),
        issuedAt: Instant = now - 1.minutes,
        expiresAt: Instant = issuedAt + 1.hours,
        secret: String? = null,
    ): String {
        val builder = JWT.create()
            .withIssuer(issuer)
            .withSubject(uid)
            .withIssuedAt(issuedAt.toJavaInstant())
            .withExpiresAt(expiresAt.toJavaInstant())
            .withClaim("role", "authenticated")

        if (roles.isNotEmpty()) {
            builder.withClaim("roles", roles)
        }

        val algorithm = if (secret != null) Algorithm.HMAC256(secret) else Algorithm.none()
        return builder.sign(algorithm)
    }

    private class MockUserFetcher(private val users: MutableMap<String, SupabaseUser> = mutableMapOf()) : SupabaseUserFetcher {
        var callCount = 0

        fun register(token: String, user: SupabaseUser) {
            users[token] = user
        }

        override suspend fun fetch(token: String): SupabaseUser? {
            callCount++
            return users[token]
        }
    }

    private fun ApplicationTestBuilder.setupApp(
        settings: SupabaseSettings = SupabaseSettings(URL, ANON_KEY),
        fetcher: SupabaseUserFetcher = MockUserFetcher(),
    ) {
        application {
            install(Authentication) {
                supabase("supabase", settings, fetcher)
            }
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = testSchema
                identities = AuthIdentities("supabase")
            }
            routing {
                authenticate("supabase") {
                    get("/whoami") {
                        val identity = call.principal<Identity>()
                        call.respondText(identity?.id ?: "none")
                    }
                    get("/roles") {
                        val identity = call.principal<Identity>()
                        call.respondText(identity?.roles?.sorted()?.joinToString(",") ?: "")
                    }
                }
            }
        }
    }

    @Test
    fun authenticatesViaFetcher() = testApplication {
        val fetcher = MockUserFetcher()
        val token = supabaseToken("user-123", roles = listOf("writer"))
        fetcher.register(token, SupabaseUser("user-123", roles = setOf("writer")))

        setupApp(fetcher = fetcher)

        val response = client.get("/whoami") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("user-123", response.bodyAsText())

        val rolesResponse = client.get("/roles") { bearerAuth(token) }
        assertEquals("writer", rolesResponse.bodyAsText())
        // Verified cache: fetcher was called only once despite two API requests
        assertEquals(1, fetcher.callCount)
    }

    @Test
    fun authenticatesViaHmacSecret() = testApplication {
        val settings = SupabaseSettings(URL, ANON_KEY, jwtSecret = SECRET)
        val token = supabaseToken("user-hmac", roles = listOf("admin", "writer"), secret = SECRET)

        setupApp(settings = settings)

        val response = client.get("/whoami") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("user-hmac", response.bodyAsText())

        val rolesResponse = client.get("/roles") { bearerAuth(token) }
        assertEquals("admin,writer", rolesResponse.bodyAsText())
    }

    @Test
    fun enforcesBaseKtPermissions() = testApplication {
        val settings = SupabaseSettings(URL, ANON_KEY, jwtSecret = SECRET)
        val writerToken = supabaseToken("writer-1", roles = listOf("writer"), secret = SECRET)
        val readerToken = supabaseToken("reader-1", roles = emptyList(), secret = SECRET)

        setupApp(settings = settings)

        // Reader can read but not create
        val readResp = client.get("/api/posts") { bearerAuth(readerToken) }
        assertEquals(HttpStatusCode.OK, readResp.status)

        val forbiddenCreate = client.post("/api/posts") {
            bearerAuth(readerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"title": "New Post"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenCreate.status)

        // Writer can create
        val allowedCreate = client.post("/api/posts") {
            bearerAuth(writerToken)
            contentType(ContentType.Application.Json)
            setBody("""{"title": "New Post"}""")
        }
        assertEquals(HttpStatusCode.Created, allowedCreate.status)
    }

    @Test
    fun rejectsExpiredToken() = testApplication {
        val settings = SupabaseSettings(URL, ANON_KEY, jwtSecret = SECRET)
        val expiredToken = supabaseToken(
            "user-expired",
            issuedAt = now - 2.hours,
            expiresAt = now - 1.hours,
            secret = SECRET,
        )

        setupApp(settings = settings)

        val response = client.get("/whoami") { bearerAuth(expiredToken) }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun ignoresOtherIssuersSoChainedProvidersCanHandleThem() = testApplication {
        val settings = SupabaseSettings(URL, ANON_KEY, jwtSecret = SECRET)
        val foreignToken = supabaseToken(
            "user-foreign",
            issuer = "https://securetoken.google.com/firebase-project",
            secret = SECRET,
        )

        setupApp(settings = settings)

        val response = client.get("/whoami") { bearerAuth(foreignToken) }
        // Rejected by supabase because issuer doesn't match
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
