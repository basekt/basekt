package io.github.basekt.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import kotlin.coroutines.cancellation.CancellationException

/** Roles and scope attributes the server grants a caller, on top of the ones its credentials carry. */
public data class Grants(
    /** The roles to add. */
    val roles: Set<String> = emptySet(),
    /** The attribute values to add, by attribute name. */
    val attributes: Map<String, Set<String>> = emptyMap(),
) {
    /** Grants with no roles or attributes. */
    public companion object {
        /** Grants nothing. */
        public val None: Grants = Grants()
    }
}

/**
 * Where a caller's roles come from when they aren't in its credentials, such as a list of ids kept in the server's
 * configuration, or records in a resource that change without a new sign-in. Add it to any resolver with
 * [withServerRoles]:
 *
 * ```kotlin
 * install(BaseKt) {
 *     identities = AuthIdentities("jwt").withServerRoles(
 *         ServerRoles.from(environment.config.config("basekt.roles")),
 *         ServerRoles { identity, _ -> Grants(roles = memberships.rolesOf(identity.id)) },
 *     )
 * }
 * ```
 *
 * Grants only add: the caller keeps every role and attribute its credentials carry, and an attribute both name holds
 * the values of both. A source that throws grants nothing, and the call fails; see [withServerRoles].
 */
public fun interface ServerRoles {
    /** Returns what the server grants [identity] for [call], or [Grants.None]. */
    public suspend fun grantsFor(identity: Identity, call: ApplicationCall): Grants

    /** Ready-made sources. */
    public companion object {
        /** Grants each role of [idsByRole] to the caller ids it lists. */
        public fun byId(idsByRole: Map<String, Collection<String>>): ServerRoles {
            val blankRoles = idsByRole.keys.filter { it.isBlank() }
            require(blankRoles.isEmpty()) { "Server roles need names; one is blank." }
            for ((role, ids) in idsByRole) require(ids.none { it.isBlank() }) { "Server role '$role' lists a blank id." }
            val rolesById = idsByRole.flatMap { (role, ids) -> ids.map { it to role } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
            return ServerRoles { identity, _ -> rolesById[identity.id]?.let { Grants(roles = it) } ?: Grants.None }
        }

        /**
         * Reads a role per key of [config], such as the application's `basekt.roles` section, each with the list of
         * caller ids that hold it:
         *
         * ```hocon
         * basekt.roles {
         *     operator = ["uid-1", "uid-2"]
         *     moderator = ["uid-3"]
         * }
         * ```
         */
        public fun from(config: ApplicationConfig): ServerRoles = byId(
            config.toMap().mapValues { (role, ids) ->
                require(ids is List<*> && ids.all { it is String }) {
                    "Server role '$role' must be a list of caller ids, such as $role = [\"id-1\", \"id-2\"]."
                }
                ids.map { it as String }
            },
        )

        /** Asks each of [sources] in turn and adds up what they grant. */
        public fun allOf(vararg sources: ServerRoles): ServerRoles {
            val all = sources.toList()
            return ServerRoles { identity, call -> all.fold(Grants.None) { grants, source -> grants + source.grantsFor(identity, call) } }
        }
    }
}

/** These grants and [other]'s together. */
public operator fun Grants.plus(other: Grants): Grants = Grants(roles + other.roles, attributes.mergedWith(other.attributes))

/** [identity] with [grants] added to its roles and attributes. */
public operator fun Identity.plus(grants: Grants): Identity =
    copy(roles = roles + grants.roles, attributes = attributes.mergedWith(grants.attributes))

private fun Map<String, Set<String>>.mergedWith(other: Map<String, Set<String>>): Map<String, Set<String>> =
    (keys + other.keys).associateWith { this[it].orEmpty() + other[it].orEmpty() }.filterValues { it.isNotEmpty() }

/**
 * This resolver, with what [sources] grant added to each caller it finds. Anonymous calls stay anonymous and ask no
 * source. A route scope this resolver needs, such as `authenticate { }`, is kept.
 *
 * Each call is resolved once: the identity is kept on the call, so resolving it again, as every route and permission
 * check of the call may, doesn't ask the sources again.
 *
 * A source that throws an [ApiException] answers the call with its error. Any other failure answers
 * [ErrorCode.UNAVAILABLE], logged with its cause, and grants nothing, so a lookup that fails never lets a call through
 * on the roles it would have found, nor on the credentials' roles alone.
 */
public fun IdentityResolver.withServerRoles(vararg sources: ServerRoles): IdentityResolver {
    val source = ServerRoles.allOf(*sources)
    return if (this is ScopedIdentityResolver) ScopedWithServerRoles(this, source) else WithServerRoles(this, source)
}

private val ResolvedKey = AttributeKey<MutableMap<IdentityResolver, Resolved>>("BaseKtServerRoles")

/** The identity of a call, `null` when it is anonymous. */
private class Resolved(val identity: Identity?)

private open class WithServerRoles(private val inner: IdentityResolver, private val source: ServerRoles) : IdentityResolver {
    override suspend fun resolve(call: ApplicationCall): Identity? {
        val resolved = call.attributes.computeIfAbsent(ResolvedKey) { mutableMapOf() }
        resolved[this]?.let { return it.identity }
        val identity = inner.resolve(call)?.let { it + grantsFor(it, call) }
        resolved[this] = Resolved(identity)
        return identity
    }

    private suspend fun grantsFor(identity: Identity, call: ApplicationCall): Grants = try {
        source.grantsFor(identity, call)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        throw ApiException(ErrorCode.UNAVAILABLE, "The caller's roles couldn't be looked up. Try again later.", e)
    }
}

private class ScopedWithServerRoles(private val inner: ScopedIdentityResolver, source: ServerRoles) :
    WithServerRoles(inner, source), ScopedIdentityResolver {
    override fun scope(parent: Route, routes: Route.() -> Unit) = inner.scope(parent, routes)
}
