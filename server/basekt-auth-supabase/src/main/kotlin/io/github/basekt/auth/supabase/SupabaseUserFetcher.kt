package io.github.basekt.auth.supabase

import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Fetches user profiles from Supabase Auth (GoTrue). Tests can pass a mock fetcher; production uses [gotrue].
 */
public fun interface SupabaseUserFetcher {
    /** Fetches the user for [token]. Returns null if invalid or cannot be fetched. */
    public suspend fun fetch(token: String): SupabaseUser?

    public companion object {
        /**
         * Creates a fetcher that contacts `${url.trimEnd('/')}/auth/v1/user` using the JDK's standard [HttpClient].
         */
        public fun gotrue(
            url: String,
            anonKey: String,
            rolesClaim: String = "roles",
            attributeClaims: List<String> = emptyList(),
            timeout: Duration = 10.seconds,
        ): SupabaseUserFetcher {
            val endpoint = URI.create("${url.trimEnd('/')}/auth/v1/user")
            return HttpSupabaseUserFetcher(endpoint, anonKey, rolesClaim, attributeClaims, timeout)
        }
    }
}

/** Fetches user info via standard Java HttpClient. */
private class HttpSupabaseUserFetcher(
    private val endpoint: URI,
    private val anonKey: String,
    private val rolesClaim: String,
    private val attributeClaims: List<String>,
    private val timeout: Duration,
) : SupabaseUserFetcher {
    private val client by lazy { HttpClient.newBuilder().connectTimeout(timeout.toJavaDuration()).build() }

    override suspend fun fetch(token: String): SupabaseUser? {
        val request = HttpRequest.newBuilder(endpoint)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .timeout(timeout.toJavaDuration())
            .GET()
            .build()
        val response = runCatching {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        }.getOrNull() ?: return null

        if (response.statusCode() != 200) return null
        return SupabaseUser.parse(response.body(), rolesClaim, attributeClaims)
    }
}
