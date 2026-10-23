package io.github.basekt.auth

import io.github.basekt.server.ApiError
import io.github.basekt.server.ApiException
import io.github.basekt.server.ErrorCode
import io.github.basekt.server.Identity
import io.github.basekt.server.ScopedIdentityResolver
import io.github.basekt.server.forCall
import io.github.basekt.server.toJson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.ForbiddenResponse
import io.ktor.server.auth.UnauthorizedResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route

/**
 * Finds out who made a call with Ktor's `Authentication` plugin. Set it as `identities` in `install(BaseKt)`:
 *
 * ```kotlin
 * install(Authentication) {
 *     baseKtJwt("jwt", JwtSettings.from(environment.config.config("auth.jwt")))
 *     bearerIdentities("keys") { token -> apiKeys[token] }
 * }
 * install(BaseKt) {
 *     identities = AuthIdentities("jwt", "keys")
 *     // ...
 * }
 * ```
 *
 * BaseKt's routes are built inside `authenticate(*providers, optional = true)`, so install `Authentication` first.
 * - A call without credentials is anonymous, and the schema decides what it may do.
 * - A call whose credentials every provider rejects gets a 401 [ApiError], with the providers' `WWW-Authenticate`
 *   challenges.
 * - The principal of an accepted call becomes an [Identity] through [roles]. A principal that maps to no identity is
 *   refused with 401 too.
 *
 * Permission checks, and their 401 and 403 errors, stay in the service, so they're the same on every transport.
 *
 * @param providers the names of the `Authentication` providers to try, in order. `null` names the unnamed one.
 * @param roles how principals become identities.
 */
public class AuthIdentities(
    private val providers: List<String?>,
    private val roles: PrincipalRoles = PrincipalRoles.Default,
) : ScopedIdentityResolver {
    init {
        require(providers.isNotEmpty()) { "AuthIdentities needs at least one authentication provider." }
    }

    /** Tries [providers] in order, and maps principals with [roles]. */
    public constructor(vararg providers: String?, roles: PrincipalRoles = PrincipalRoles.Default) :
        this(providers.toList(), roles)

    override fun scope(parent: Route, routes: Route.() -> Unit) {
        parent.authenticate(*providers.toTypedArray(), optional = true) {
            install(AuthErrorBodies)
            routes()
        }
    }

    override suspend fun resolve(call: ApplicationCall): Identity? {
        val principal = call.principal<Any>() ?: return null
        return roles.identityOf(principal)
            ?: throw ApiException(ErrorCode.UNAUTHORIZED, "The credentials don't name a caller.")
    }
}

/** Turns the empty 401 and 403 responses of Ktor's authentication challenges into [ApiError] bodies. */
private val AuthErrorBodies = createRouteScopedPlugin("BaseKtAuthErrorBodies") {
    onCallRespond { call, body ->
        val error = when (body) {
            is UnauthorizedResponse -> ApiError(ErrorCode.UNAUTHORIZED, "The credentials are invalid or expired.")
            is ForbiddenResponse -> ApiError(ErrorCode.FORBIDDEN, "The credentials don't allow this call.")
            else -> return@onCallRespond
        }
        transformBody {
            // Keep the challenges, such as WWW-Authenticate, that the empty response carried.
            for ((name, values) in body.headers.entries()) {
                if (name != HttpHeaders.ContentLength) values.forEach { call.response.headers.append(name, it) }
            }
            TextContent(error.forCall(call).toJson(), ContentType.Application.Json, HttpStatusCode.fromValue(error.error.status))
        }
    }
}
