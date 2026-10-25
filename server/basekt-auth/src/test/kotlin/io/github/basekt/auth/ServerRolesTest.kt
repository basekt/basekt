package io.github.basekt.auth

import com.typesafe.config.ConfigFactory
import io.github.basekt.data.Comparison
import io.github.basekt.data.Filter
import io.github.basekt.data.Query
import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.github.basekt.server.ApiException
import io.github.basekt.server.BaseKt
import io.github.basekt.server.ErrorCode
import io.github.basekt.server.Grants
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.github.basekt.server.ScopedIdentityResolver
import io.github.basekt.server.ServerRoles
import io.github.basekt.server.baseKt
import io.github.basekt.server.plus
import io.github.basekt.server.withServerRoles
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours

/** A made-up forum: anyone reads threads, members start them, and only moderators remove them. */
private val forumSchema: Schema = schema {
    resource("threads") {
        string("subject") { required() }
        permissions { read(everyone); create("member"); delete("moderator") }
    }
}

/**
 * A made-up rota: coordinators plan shifts, and rostered people see only their own sites' shifts. Who is rostered
 * where lives in the `assignments` resource, not in any token.
 */
private val rotaSchema: Schema = schema {
    resource("assignments") {
        string("personId") { required() }
        string("role") { required() }
        string("site")
        permissions { read("coordinator"); write("coordinator") }
    }
    resource("shifts") {
        string("site") { required() }
        string("starts") { required() }
        permissions {
            read("rostered", "coordinator"); write("coordinator")
            scope("site", attribute = "site", unless = setOf("coordinator"))
        }
    }
}

private val jwt = JwtSettings(issuer = "tests", audience = "basekt-tests", secret = "a-test-secret-that-is-long-enough")

private fun token(id: String, vararg roles: String, attributes: Map<String, Set<String>> = emptyMap()) =
    jwt.issue(Identity(id, roles.toSet(), attributes), validFor = 1.hours)

/** Roles from the rota's `assignments` records: each one grants its role, and its site as a `site` attribute. */
private val assignedRoles = ServerRoles { identity, call ->
    val runtime = call.application.baseKt
    val assignments = runtime.schema.resource("assignments")!!
    val mine = runtime.dataSource.list(assignments, Query(Filter.Compare("personId", Comparison.EQ, Value.Text(identity.id)))).items
    Grants(
        roles = mine.mapNotNull { (it["role"] as Value.Text?)?.value }.toSet(),
        attributes = mapOf("site" to mine.mapNotNull { (it["site"] as Value.Text?)?.value }.toSet()),
    )
}

/** Callers from JWTs, with the `site` claim as an attribute. */
private fun jwtIdentities() = AuthIdentities("jwt", roles = PrincipalRoles.jwt(attributeClaims = listOf("site")))

class ServerRolesTest {
    private fun ApplicationTestBuilder.app(schema: Schema, data: InMemoryDataSource = InMemoryDataSource(), roles: () -> IdentityResolver) {
        application {
            install(Authentication) { baseKtJwt("jwt", jwt) }
            install(BaseKt) {
                dataSource = data
                this.schema = schema
                identities = roles()
            }
        }
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String): HttpResponse =
        client.post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `roles from configuration add to the token's roles`() = testApplication {
        val config = HoconApplicationConfig(ConfigFactory.parseString("""basekt.roles { moderator = ["mo"], member = ["guest"] }"""))
        app(forumSchema) { jwtIdentities().withServerRoles(ServerRoles.from(config.config("basekt.roles"))) }

        val created = post("/api/threads", """{"subject": "Hello"}""", token("mo", "member"))
        assertEquals(HttpStatusCode.Created, created.status)
        val id = created.json().getValue("id").jsonPrimitive.content
        // A member by token only can't remove it; the moderator by configuration can, and stays a member too.
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/threads/$id") { bearerAuth(token("mel", "member")) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/threads/$id") { bearerAuth(token("mo", "member")) }.status)
        // A caller with no roles in its token gets the configured ones.
        assertEquals(HttpStatusCode.Created, post("/api/threads", """{"subject": "Hi"}""", token("guest")).status)
        // Anonymous callers stay anonymous.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/threads") { contentType(ContentType.Application.Json); setBody("""{"subject": "?"}""") }.status)
    }

    @Test
    fun `roles and scope attributes from a lookup in a resource`() = testApplication {
        val data = InMemoryDataSource()
        app(rotaSchema, data) { jwtIdentities().withServerRoles(assignedRoles) }
        val coordinator = token("cora", "coordinator")
        for (body in listOf(
            """{"personId": "ray", "role": "rostered", "site": "north"}""",
            """{"site": "north", "starts": "06:00"}""",
            """{"site": "south", "starts": "07:00"}""",
        )) {
            val path = if ("personId" in body) "/api/assignments" else "/api/shifts"
            assertEquals(HttpStatusCode.Created, post(path, body, coordinator).status)
        }

        suspend fun sites(token: String): List<String> {
            val response = client.get("/api/shifts") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            return response.json().getValue("items").jsonArray.map { it.jsonObject.getValue("site").jsonPrimitive.content }
        }
        assertEquals(listOf("north"), sites(token("ray")))
        // A token's own site attribute is kept, and the looked-up one is added to it.
        assertEquals(listOf("north", "south"), sites(token("ray", attributes = mapOf("site" to setOf("south")))).sorted())
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/shifts") { bearerAuth(token("nobody")) }.status)
    }

    @Test
    fun `configuration and a lookup combine with the JWT's roles and attributes`() = testApplication {
        val config = MapApplicationConfig().apply { put("coordinator", listOf("lead")) }
        app(rotaSchema) {
            jwtIdentities().withServerRoles(ServerRoles.from(config), assignedRoles)
        }
        val lead = token("lead", "rostered", attributes = mapOf("site" to setOf("east")))
        assertEquals(HttpStatusCode.Created, post("/api/shifts", """{"site": "west", "starts": "08:00"}""", lead).status)
        assertEquals(HttpStatusCode.Created, post("/api/assignments", """{"personId": "lead", "role": "rostered", "site": "west"}""", lead).status)

        assertEquals(
            Identity("lead", setOf("rostered", "coordinator"), mapOf("site" to setOf("east", "north"))),
            Identity("lead", setOf("rostered"), mapOf("site" to setOf("east"))) +
                Grants(setOf("coordinator"), mapOf("site" to setOf("north"))),
        )
        assertEquals(Identity("x") + Grants.None, Identity("x"))
        // The resolver keeps the authenticate { } scope it needs.
        assertIs<ScopedIdentityResolver>(jwtIdentities().withServerRoles(ServerRoles.byId(mapOf("coordinator" to listOf("lead")))))
    }

    @Test
    fun `a failing lookup grants nothing and answers a typed 503`() = testApplication {
        val down = ServerRoles { _, _ -> throw IOException("the role store is down") }
        app(forumSchema) { jwtIdentities().withServerRoles(ServerRoles.byId(mapOf("moderator" to listOf("mo"))), down) }

        // Even a call the token's roles alone allow fails: the caller's roles are unknown, not empty.
        val response = post("/api/threads", """{"subject": "Hello"}""", token("mel", "member"))
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("unavailable", response.json().getValue("error").jsonPrimitive.content)
        assertEquals(false, "role store" in response.bodyAsText())
        // Anonymous calls ask no source, so public reads keep working.
        assertEquals(HttpStatusCode.OK, client.get("/api/threads").status)
    }

    @Test
    fun `a lookup can answer with its own typed error`() = testApplication {
        val banned = ServerRoles { identity, _ ->
            if (identity.id == "troll") throw ApiException(ErrorCode.FORBIDDEN, "This account is suspended.") else Grants.None
        }
        app(forumSchema) { jwtIdentities().withServerRoles(banned) }
        val refused = post("/api/threads", """{"subject": "Hello"}""", token("troll", "member"))
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("This account is suspended.", refused.json().getValue("message").jsonPrimitive.content)
        assertEquals(HttpStatusCode.Created, post("/api/threads", """{"subject": "Hello"}""", token("mel", "member")).status)
    }

    @Test
    fun `each call looks its roles up once`() = testApplication {
        val lookups = AtomicInteger()
        val identities = jwtIdentities().withServerRoles(ServerRoles { _, _ -> lookups.incrementAndGet(); Grants(setOf("member")) })
        application {
            install(Authentication) { baseKtJwt("jwt", jwt) }
            install(BaseKt) {
                dataSource = InMemoryDataSource()
                schema = forumSchema
                this.identities = identities
            }
            routing {
                authenticate("jwt") {
                    get("/twice") {
                        val first = identities.resolve(call)
                        val second = identities.resolve(call)
                        call.respondText("${first == second} ${second!!.roles}")
                    }
                }
            }
        }
        assertEquals("true [member]", client.get("/twice") { bearerAuth(token("mel")) }.bodyAsText())
        assertEquals(1, lookups.get())
        assertEquals(HttpStatusCode.Created, post("/api/threads", """{"subject": "Hello"}""", token("mel")).status)
        assertEquals(2, lookups.get())
    }

    @Test
    fun `configuration is validated`() {
        fun parse(hocon: String) = ServerRoles.from(HoconApplicationConfig(ConfigFactory.parseString(hocon)))
        assertFailsWith<IllegalArgumentException> { parse("""moderator = "mo"""") }
        assertFailsWith<IllegalArgumentException> { parse("""moderator = [""]""") }
        assertFailsWith<IllegalArgumentException> { parse("""moderator { nested = ["mo"] }""") }
        assertFailsWith<IllegalArgumentException> { ServerRoles.byId(mapOf(" " to listOf("mo"))) }
        parse("""moderator = ["mo"], member = []""")
    }
}
