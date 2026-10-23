package io.github.basekt.auth

import com.auth0.jwt.JWT
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** A made-up wiki: anyone reads pages, editors write them, only admins delete them. */
private val wikiSchema: Schema = schema {
    resource("pages") {
        string("title") { required() }
        string("body")
        permissions { read(everyone); create("editor"); update("editor"); delete("admin") }
    }
}

/** A made-up journal: private to readers and writers, and never deleted. */
private val journalSchema: Schema = schema {
    resource("entries") {
        string("text") { required() }
        permissions { read("reader", "writer"); create("writer"); update("writer") }
    }
}

private val jwt = JwtSettings(issuer = "tests", audience = "basekt-tests", secret = "a-test-secret-that-is-long-enough")
private val apiKeys = mapOf("key-editor" to Identity("bot", setOf("editor")))

class AuthRoutesTest {
    private fun ApplicationTestBuilder.app(schema: Schema, roles: PrincipalRoles = PrincipalRoles.Default) {
        application {
            install(Authentication) {
                baseKtJwt("jwt", jwt)
                bearerIdentities("keys") { token -> apiKeys[token] }
            }
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                this.schema = schema
                identities = AuthIdentities("jwt", "keys", roles = roles)
            }
            routing {
                get("/open") { call.respondText("open") }
                authenticate("jwt") { get("/mine") { call.respondText(call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()!!.subject!!) } }
            }
        }
    }

    private fun token(id: String, vararg roles: String) = jwt.issue(Identity(id, roles.toSet()), validFor = 1.hours)

    private suspend fun HttpClient.createPage(title: String, auth: HttpRequestBuilder.() -> Unit = {}): HttpResponse =
        post("/api/pages") {
            auth()
            contentType(ContentType.Application.Json)
            setBody("""{"title": "$title"}""")
        }

    private suspend fun HttpResponse.errorCode(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonPrimitive.content

    @Test
    fun `anonymous callers may read public resources but not write them`() = testApplication {
        app(wikiSchema)
        assertEquals(HttpStatusCode.OK, client.get("/api/pages").status)
        val refused = client.createPage("Home")
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals("unauthorized", refused.errorCode())
        assertEquals("open", client.get("/open").bodyAsText())
    }

    @Test
    fun `invalid, expired and foreign tokens get a typed 401 with a challenge`() = testApplication {
        app(wikiSchema)
        val expired = jwt.issue(Identity("ed", setOf("editor")), validFor = 1.minutes, now = Clock.System.now() - 2.hours)
        val foreign = JwtSettings("someone-else", "basekt-tests", "another-secret-that-is-long-enough").issue(Identity("ed", setOf("editor")), 1.hours)
        for (bad in listOf("not-a-token", expired, foreign)) {
            // Even a read that anonymous callers may make: bad credentials are an error, not anonymity.
            val response = client.get("/api/pages") { bearerAuth(bad) }
            assertEquals(HttpStatusCode.Unauthorized, response.status, bad)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals("unauthorized", response.errorCode())
            assertNotNull(response.headers[HttpHeaders.WWWAuthenticate])
        }
    }

    @Test
    fun `a caller without the role gets a typed 403`() = testApplication {
        app(wikiSchema)
        val response = client.createPage("Home") { bearerAuth(token("viv", "viewer")) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("forbidden", response.errorCode())
    }

    @Test
    fun `each operation checks its own roles`() = testApplication {
        app(wikiSchema)
        val editor = token("ed", "editor")
        val created = client.createPage("Home") { bearerAuth(editor) }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content

        val patched = client.patch("/api/pages/$id") {
            bearerAuth(editor)
            contentType(ContentType.Application.Json)
            setBody("""{"body": "Welcome"}""")
        }
        assertEquals(HttpStatusCode.OK, patched.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/pages/$id") { bearerAuth(editor) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/pages/$id") { bearerAuth(token("ada", "admin")) }.status)
    }

    @Test
    fun `an opaque bearer key resolves to its identity`() = testApplication {
        app(wikiSchema)
        assertEquals(HttpStatusCode.Created, client.createPage("From a bot") { bearerAuth("key-editor") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.createPage("Nope") { bearerAuth("key-unknown") }.status)
    }

    @Test
    fun `private resources refuse anonymous reads and check roles per operation`() = testApplication {
        app(journalSchema)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/entries").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/entries") { bearerAuth(token("rae", "reader")) }.status)

        suspend fun write(token: String) = client.post("/api/entries") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"text": "Dear diary"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, write(token("rae", "reader")).status)
        val created = write(token("wes", "writer"))
        assertEquals(HttpStatusCode.Created, created.status)
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
        // Nobody may delete entries, whatever their roles.
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/entries/$id") { bearerAuth(token("wes", "writer", "admin")) }.status)
    }

    @Test
    fun `the app's own authenticated routes keep working`() = testApplication {
        app(wikiSchema)
        assertEquals("ed", client.get("/mine") { bearerAuth(token("ed")) }.bodyAsText())
    }

    @Test
    fun `custom mappings read other principals and roles claims`() = testApplication {
        val grouped = JwtSettings("tests", "basekt-tests", "a-test-secret-that-is-long-enough", rolesClaim = "groups")
        val roles = PrincipalRoles.firstOf(
            PrincipalRoles.of<UserIdPrincipal> { Identity(it.name, setOf("editor")) },
            PrincipalRoles.jwt(rolesClaim = "groups"),
        )
        application {
            install(Authentication) { baseKtJwt("jwt", grouped) }
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = wikiSchema
                identities = AuthIdentities("jwt", roles = roles)
            }
        }
        val token = grouped.issue(Identity("ed", setOf("editor")), 1.hours)
        assertEquals(HttpStatusCode.Created, client.createPage("Grouped") { bearerAuth(token) }.status)
        assertEquals(setOf("editor"), roles.identityOf(UserIdPrincipal("u"))!!.roles)
        assertEquals(null, PrincipalRoles.Default.identityOf("not a principal"))
    }

    @Test
    fun `attributes travel as claims and come back through the mapping`() {
        val identity = Identity("ed", setOf("editor"), mapOf("site" to setOf("north", "south")))
        val payload = JWT.decode(jwt.issue(identity, 1.hours))
        assertEquals(identity, PrincipalRoles.jwt(attributeClaims = listOf("site", "team")).identityOf(JWTPrincipal(payload)))
        assertEquals(emptyMap(), PrincipalRoles.jwt().identityOf(JWTPrincipal(payload))!!.attributes)
        assertFailsWith<IllegalArgumentException> { jwt.issue(Identity("ed", attributes = mapOf("sub" to setOf("x"))), 1.hours) }
        assertFailsWith<IllegalArgumentException> { jwt.issue(Identity("ed", attributes = mapOf("roles" to setOf("admin"))), 1.hours) }
    }
}
