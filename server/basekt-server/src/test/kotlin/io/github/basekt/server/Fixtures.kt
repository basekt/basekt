package io.github.basekt.server

import io.github.basekt.schema.FieldValidator
import io.github.basekt.schema.Resource
import io.github.basekt.schema.Schema
import io.github.basekt.schema.Value
import io.github.basekt.schema.schema
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A made-up library catalogue: authors and their books, readable by anyone, written by librarians. */
val catalogueSchema: Schema = schema {
    resource("authors") {
        string("name") { required() }
        hasMany("books", "books", foreignField = "authorId")
        permissions { read(everyone); write("librarian") }
    }
    resource("books") {
        string("title") { required(); length(max = 200) }
        string("isbn") { custom("isbn") }
        enum("format", listOf("paper", "ebook", "audio"))
        long("loans") { min(0) }
        belongsTo("author", "authors")
        permissions { read(everyone); create("librarian"); update("librarian"); delete("admin") }
    }
}

/** A made-up task tracker: private to signed-in members, with estimates and due dates. */
val trackerSchema: Schema = schema {
    resource("projects") {
        string("title") { required() }
        hasMany("tasks", "tasks", foreignField = "projectId")
        permissions { read(authenticated); write("lead") }
    }
    resource("tasks") {
        string("summary") { required() }
        belongsTo("project", "projects") { required() }
        int("priority") { min(1); max(5) }
        decimal("estimate") { min(0) }
        instant("due")
        bool("done")
        permissions { read(authenticated); create(authenticated); update(authenticated); delete("lead") }
    }
}

/**
 * A made-up group of clinics: staff see and book only their own clinics' appointments, and directors see every
 * clinic. The `clinic` attribute lists a caller's clinics.
 */
val clinicSchema: Schema = schema {
    resource("clinics") {
        string("name") { required() }
        hasMany("appointments", "appointments", foreignField = "clinicId")
        permissions { read(authenticated); write("director"); scope(Resource.ID, attribute = "clinic", unless = setOf("director")) }
    }
    resource("appointments") {
        belongsTo("clinic", "clinics") { required() }
        string("patient") { required() }
        instant("at")
        permissions { read("staff", "director"); write("staff", "director"); scope("clinicId", attribute = "clinic", unless = setOf("director")) }
    }
}

/** A made-up shared journal: every member reads every entry, and only an entry's author may change it. */
val journalSchema: Schema = schema {
    resource("entries") {
        string("authorId")
        string("body") { required() }
        permissions { read(authenticated); write(authenticated); ownedBy("authorId", on = writes) }
    }
}

val isbnValidator: FieldValidator = FieldValidator { value ->
    if ((value as Value.Text).value.replace("-", "").length in setOf(10, 13)) null else "An ISBN has 10 or 13 digits."
}

/**
 * Test-only identities: `X-User` names the caller, `X-Roles` lists roles, and each `X-Attr-<name>` header lists the
 * values of an attribute. No header means anonymous.
 */
val headerIdentities: IdentityResolver = IdentityResolver { call ->
    call.request.headers["X-User"]?.let { user ->
        val attributes = call.request.headers.names().filter { it.startsWith("X-Attr-", ignoreCase = true) }
            .associate { it.substring("X-Attr-".length).lowercase() to call.request.headers[it]!!.names() }
        Identity(user, call.request.headers["X-Roles"].orEmpty().names(), attributes)
    }
}

private fun String.names(): Set<String> = split(',').filter { it.isNotBlank() }.toSet()

/** A caller [user] with [roles] and [attributes], through [headerIdentities]. */
fun HttpRequestBuilder.caller(user: String, roles: List<String> = emptyList(), attributes: Map<String, List<String>> = emptyMap()) {
    header("X-User", user)
    header("X-Roles", roles.joinToString(","))
    for ((name, values) in attributes) header("X-Attr-$name", values.joinToString(","))
}

fun HttpRequestBuilder.asUser(vararg roles: String) {
    header("X-User", "u1")
    header("X-Roles", roles.joinToString(","))
}

fun HttpRequestBuilder.json(text: String) {
    contentType(ContentType.Application.Json)
    setBody(text)
}

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpClient.postJson(path: String, body: String, vararg roles: String): HttpResponse =
    post(path) { asUser(*roles); json(body) }

suspend fun HttpClient.patchJson(path: String, body: String, vararg roles: String): HttpResponse =
    patch(path) { asUser(*roles); json(body) }
