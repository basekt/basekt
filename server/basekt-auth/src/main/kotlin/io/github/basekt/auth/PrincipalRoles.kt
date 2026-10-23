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
         * Reads a [JWTPrincipal]: the id from [idClaim] (the subject by default), the roles from [rolesClaim], and an
         * attribute from each of [attributeClaims], named after its claim. Roles and attributes may be an array of
         * strings or one string of names separated by spaces or commas; a missing claim is no attribute.
         */
        public fun jwt(
            idClaim: String = "sub",
            rolesClaim: String = "roles",
            attributeClaims: List<String> = emptyList(),
        ): PrincipalRoles = of<JWTPrincipal> { principal ->
            val id = principal.payload.getClaim(idClaim).asString()?.takeIf { it.isNotBlank() } ?: return@of null
            val attributes = attributeClaims.associateWith { principal.names(it) }.filterValues { it.isNotEmpty() }
            Identity(id, principal.names(rolesClaim), attributes)
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

/** Reads [claim] as an array of strings or one string of names separated by spaces or commas. */
private fun JWTPrincipal.names(claim: String): Set<String> {
    val value = payload.getClaim(claim)
    val names = value.asList(String::class.java) ?: value.asString()?.split(' ', ',') ?: emptyList()
    return names.filter { it.isNotBlank() }.toSet()
}
