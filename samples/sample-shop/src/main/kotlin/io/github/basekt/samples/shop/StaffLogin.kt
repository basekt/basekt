package io.github.basekt.samples.shop

import io.github.basekt.auth.JwtSettings
import io.github.basekt.server.Identity
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** A staff account of the sample: an email, a password and roles, from `shop.staff`. */
internal class StaffAccount(val email: String, private val password: String, val roles: Set<String>) {
    fun accepts(given: String): Boolean = MessageDigest.isEqual(password.toByteArray(), given.toByteArray())

    companion object {
        fun list(config: ApplicationConfig): List<StaffAccount> = config.configList("shop.staff").map { entry ->
            StaffAccount(entry.property("email").getString(), entry.property("password").getString(), entry.property("roles").getList().toSet())
        }
    }
}

/**
 * The admin panel's sign-in, as a host app writes it:
 * - `POST /auth/login` with `{"email": …, "password": …}` answers `{"token": …, "refreshToken": …, "expiresIn": 900}`,
 *   a JWT signed with [jwt] that lives [tokenLife], and a refresh token;
 * - `POST /auth/refresh` with `{"refreshToken": …}` answers the same shape, with a new refresh token: each one works
 *   once, so a stolen one that was already used is refused.
 *
 * It is a demo: the accounts have plain passwords in configuration, and refresh tokens live in memory, so a restart
 * signs everyone out. A real app keeps hashed passwords and stores refresh tokens, hashed, with an expiry.
 */
internal fun Route.staffLogin(jwt: JwtSettings, accounts: List<StaffAccount>, tokenLife: Duration = 15.minutes) {
    val byEmail = accounts.associateBy { it.email.lowercase() }
    val refreshTokens = ConcurrentHashMap<String, Identity>()
    val random = SecureRandom()

    suspend fun RoutingCall.answer(identity: Identity) {
        val refreshToken = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        refreshTokens[refreshToken] = identity
        val body = buildJsonObject {
            put("token", jwt.issue(identity, tokenLife))
            put("refreshToken", refreshToken)
            put("expiresIn", tokenLife.inWholeSeconds)
        }
        respondText(body.toString(), ContentType.Application.Json)
    }

    post("/auth/login") {
        val body = call.jsonBody()
        val account = body?.text("email")?.lowercase()?.let(byEmail::get)
        if (account == null || !account.accepts(body.text("password").orEmpty())) {
            return@post call.unauthorized("The email or password is wrong.")
        }
        call.answer(Identity(account.email, account.roles))
    }

    post("/auth/refresh") {
        val identity = call.jsonBody()?.text("refreshToken")?.let(refreshTokens::remove)
            ?: return@post call.unauthorized("That refresh token isn't valid. Sign in again.")
        call.answer(identity)
    }
}

private suspend fun RoutingCall.jsonBody(): JsonObject? =
    runCatching { Json.parseToJsonElement(receiveText()).jsonObject }.getOrNull()

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private suspend fun RoutingCall.unauthorized(message: String) {
    val body = buildJsonObject {
        put("error", "unauthorized")
        put("message", message)
    }
    respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.Unauthorized)
}
