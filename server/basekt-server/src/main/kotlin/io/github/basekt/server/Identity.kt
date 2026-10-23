package io.github.basekt.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route

/** An authenticated caller, as BaseKt sees it: an id and the roles that [io.github.basekt.schema.Access] checks. */
public data class Identity(
    /** A stable id for the caller. */
    val id: String,
    /** The caller's roles. */
    val roles: Set<String> = emptySet(),
)

/**
 * Finds out who made a call. This is the seam an auth module plugs into: it maps the call's principal to an
 * [Identity]. BaseKt itself never authenticates anyone.
 */
public fun interface IdentityResolver {
    /** Returns the caller of [call], or `null` for an anonymous call. */
    public suspend fun resolve(call: ApplicationCall): Identity?

    /** Ready-made resolvers. */
    public companion object {
        /** Treats every call as anonymous, so only [io.github.basekt.schema.Access.Everyone] is allowed. */
        public val Anonymous: IdentityResolver = IdentityResolver { null }
    }
}

/**
 * An optional [IdentityResolver] capability, for resolvers that can only see the caller inside a route of their own,
 * such as Ktor's `authenticate { }`. BaseKt, and every module that serves routes for it, builds its routes through
 * [scope].
 */
public interface ScopedIdentityResolver : IdentityResolver {
    /** Builds [routes] under [parent], inside the route this resolver needs. */
    public fun scope(parent: Route, routes: Route.() -> Unit)
}

/** Builds [routes] under this route, inside the route [identities] needs when it is a [ScopedIdentityResolver]. */
public fun Route.withIdentities(identities: IdentityResolver, routes: Route.() -> Unit) {
    if (identities is ScopedIdentityResolver) identities.scope(this, routes) else routes()
}
