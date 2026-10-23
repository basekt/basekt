package io.github.basekt.admin

import io.github.basekt.data.memory.InMemoryDataSource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.schema
import io.github.basekt.server.BaseKt
import io.github.basekt.server.Identity
import io.github.basekt.server.IdentityResolver
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A made-up parts store: bins hold parts; keepers stock them, managers remove them, auditors read a log. */
val storeSchema: Schema = schema {
    resource("bins") {
        string("label") { required(); length(max = 20) }
        enum("zone", listOf("north", "south"))
        hasMany("parts", "parts", foreignField = "binId")
        permissions { read(authenticated); write("keeper") }
    }
    resource("parts") {
        string("name") { required() }
        string("note")
        int("quantity") { min(0) }
        decimal("cost")
        instant("countedAt")
        belongsTo("bin", "bins")
        permissions { read(authenticated); create("keeper"); update("keeper"); delete("manager") }
    }
    resource("log") {
        string("entry") { required() }
        permissions { read("auditor") }
    }
}

/** A made-up public glossary: anyone reads terms, editors write them. */
val glossarySchema: Schema = schema {
    resource("terms") {
        string("word") { required() }
        string("meaning")
        permissions { read(everyone); write("editor") }
    }
}

/** Test-only identities: `X-User` names the caller and `X-Roles` lists roles. No header means anonymous. */
val headerIdentities: IdentityResolver = IdentityResolver { call ->
    call.request.headers["X-User"]?.let { user ->
        Identity(user, call.request.headers["X-Roles"].orEmpty().split(',').filter { it.isNotBlank() }.toSet())
    }
}

fun ApplicationTestBuilder.admin(schema: Schema, admin: BaseKtAdminConfig.() -> Unit = {}) {
    application {
        install(BaseKt) {
            dataSource = InMemoryDataSource()
            this.schema = schema
            identities = headerIdentities
        }
        install(BaseKtAdmin, admin)
    }
}

fun HttpRequestBuilder.asUser(vararg roles: String) {
    header("X-User", "u1")
    header("X-Roles", roles.joinToString(","))
}

suspend fun HttpClient.postJson(path: String, body: String, vararg roles: String): HttpResponse = post(path) {
    asUser(*roles)
    contentType(ContentType.Application.Json)
    setBody(body)
}

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
