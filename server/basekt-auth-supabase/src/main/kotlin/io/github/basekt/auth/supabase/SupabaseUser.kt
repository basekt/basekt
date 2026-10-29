package io.github.basekt.auth.supabase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A user verified by Supabase Auth GoTrue service.
 *
 * @property id the user's UUID (`sub`).
 * @property email the user's email address, if present.
 * @property role the user's GoTrue role (e.g. `authenticated`).
 * @property roles roles assigned to the user from `app_metadata.roles` or `user_metadata.roles`.
 * @property attributes custom record-scope attributes from metadata.
 */
public data class SupabaseUser(
    val id: String,
    val email: String? = null,
    val role: String? = null,
    val roles: Set<String> = emptySet(),
    val attributes: Map<String, Set<String>> = emptyMap(),
) {
    public companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a JSON string returned by GoTrue's `/auth/v1/user` endpoint.
         */
        public fun parse(body: String, rolesClaim: String = "roles", attributeClaims: List<String> = emptyList()): SupabaseUser? {
            val element = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val id = element["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return null
            val email = element["email"]?.jsonPrimitive?.content
            val role = element["role"]?.jsonPrimitive?.content

            val appMetadata = element["app_metadata"]?.jsonObject
            val userMetadata = element["user_metadata"]?.jsonObject

            fun extractList(claim: String): List<String> {
                val fromTop = element[claim]
                val fromApp = appMetadata?.get(claim)
                val fromUser = userMetadata?.get(claim)
                val target = fromTop ?: fromApp ?: fromUser ?: return emptyList()

                return runCatching {
                    target.jsonArray.mapNotNull { it.jsonPrimitive.content }
                }.getOrNull() ?: target.jsonPrimitive.content.split(',', ' ').filter { it.isNotBlank() }
            }

            val roles = extractList(rolesClaim).toSet()
            val attributes = attributeClaims.associateWith { extractList(it).toSet() }.filterValues { it.isNotEmpty() }

            return SupabaseUser(
                id = id,
                email = email,
                role = role,
                roles = roles,
                attributes = attributes,
            )
        }
    }
}
