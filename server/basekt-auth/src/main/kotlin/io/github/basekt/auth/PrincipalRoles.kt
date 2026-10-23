package io.github.basekt.auth

import io.github.basekt.server.Identity
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.jwt.JWTPrincipal

/**
 * Maps the principal a Ktor authentication provider produced to the [Identity] whose roles the schema's permissions
 * check. Returning `null` means the principal names no caller, and the call is refused as unauthorized.
 */
public fun interface PrincipalRoles {
    /** Returns the identity of [principal], or `null` if it doesn't name a caller. */
    public fun identityOf(principal: Any): Identity?

    /** Ready-made mappings. */
    public companion object {
        /**
         * Understands the principals this module's adapters produce: an [Identity] as it is, a [JWTPrincipal] through
         * [jwt] with its default claims, and a [UserIdPrincipal] as a caller with no roles.
         */
        public val Default: PrincipalRoles = firstOf(of<Identity> { it }, jwt(), of<UserIdPrincipal> { Identity(it.name) })

        /**
         * Reads a [JWTPrincipal]: the id from [idClaim] (the subject by default) and the roles from [rolesClaim], which
         * may be an array of strings or one string of names separated by spaces or commas.
         */
        public fun jwt(idClaim: String = "sub", rolesClaim: String = "roles"): PrincipalRoles = of<JWTPrincipal> { principal ->
            val id = principal.payload.getClaim(idClaim).asString()?.takeIf { it.isNotBlank() } ?: return@of null
            val claim = principal.payload.getClaim(rolesClaim)
            val roles = claim.asList(String::class.java)
                ?: claim.asString()?.split(' ', ',')?.filter { it.isNotBlank() }
                ?: emptyList()
            Identity(id, roles.toSet())
        }

        /** Maps principals of type [P] with [map], and nothing else. */
        public inline fun <reified P : Any> of(crossinline map: (P) -> Identity?): PrincipalRoles =
            PrincipalRoles { principal -> (principal as? P)?.let { map(it) } }

        /** Tries each of [mappings] in turn and returns the first identity one of them finds. */
        public fun firstOf(vararg mappings: PrincipalRoles): PrincipalRoles {
            val all = mappings.toList()
            return PrincipalRoles { principal -> all.firstNotNullOfOrNull { it.identityOf(principal) } }
        }
    }
}
