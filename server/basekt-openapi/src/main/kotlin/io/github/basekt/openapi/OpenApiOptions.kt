package io.github.basekt.openapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What [toOpenApi] needs to know besides the schema: the document's title and version, and the settings of the
 * `BaseKt` install it describes. [BaseKtOpenApi] fills the server's settings in from the running install, so the
 * document always matches what is served.
 */
@Serializable
public data class OpenApiOptions(
    /** The API's name, in `info.title`. */
    val title: String = "BaseKt API",
    /** The API's own version, in `info.version`. It is the application's to choose, not BaseKt's. */
    val version: String = "1.0.0",
    /** A longer description, in Markdown, or `null`. */
    val description: String? = null,
    /** The URLs the API is served at, such as `https://api.example.com`. Empty leaves `servers` out. */
    val servers: List<String> = emptyList(),
    /** The path BaseKt's routes are mounted under. */
    val basePath: String = "/api",
    /** The page size of a list that gives no `limit`. */
    val defaultPageSize: Int = 20,
    /** The largest `limit` a list may ask for. */
    val maxPageSize: Int = 100,
    /** The most groups an aggregate may return. */
    val maxAggregateGroups: Int = 1000,
    /** The header that carries each call's request id. */
    val requestIdHeader: String = "X-Request-Id",
    /**
     * How callers authenticate, or `null` to leave security out. BaseKt doesn't know which auth module the
     * application installed, so the application says.
     */
    val security: OpenApiSecurity? = null,
) {
    /** Returns every problem with these options. An empty list means they are valid. */
    public fun problems(): List<String> = buildList {
        if (title.isBlank()) add("title can't be blank.")
        if (version.isBlank()) add("version can't be blank.")
        for (server in servers) {
            if (!serverUrl.matches(server)) add("The server '$server' is not an http(s) URL or an absolute path.")
        }
        if (!basePath.startsWith('/')) add("basePath '$basePath' must start with '/'.")
        if (maxPageSize < 1 || defaultPageSize !in 1..maxPageSize) {
            add("Page sizes must satisfy 1 <= default ($defaultPageSize) <= max ($maxPageSize).")
        }
        if (maxAggregateGroups < 1) add("maxAggregateGroups must be at least 1.")
        if (!headerName.matches(requestIdHeader)) add("requestIdHeader '$requestIdHeader' is not a header name.")
        when (val scheme = security) {
            null -> {}
            is OpenApiSecurity.ApiKey -> if (!headerName.matches(scheme.header)) add("The API key header '${scheme.header}' is not a header name.")
            is OpenApiSecurity.Bearer -> if (scheme.format?.isBlank() == true) add("The bearer format can't be blank; leave it null instead.")
        }
    }
}

/** How callers authenticate, as an OpenAPI security scheme named `basekt.auth`. */
@Serializable
public sealed interface OpenApiSecurity {
    /** A bearer token in the `Authorization` header, such as a JWT or an ID token from an identity provider. */
    @Serializable
    @SerialName("bearer")
    public data class Bearer(
        /** A hint of what the token is, such as `JWT`, or `null`. */
        val format: String? = null,
    ) : OpenApiSecurity

    /** A key in a request [header] of its own. */
    @Serializable
    @SerialName("apiKey")
    public data class ApiKey(
        /** The header that carries the key. */
        val header: String,
    ) : OpenApiSecurity
}

private val serverUrl = Regex("""(https?://[^\s/]+(/\S*)?|/\S*)""")

/** An HTTP header name: a token of RFC 9110. */
private val headerName = Regex("""[!#$%&'*+.^_`|~0-9A-Za-z-]+""")
