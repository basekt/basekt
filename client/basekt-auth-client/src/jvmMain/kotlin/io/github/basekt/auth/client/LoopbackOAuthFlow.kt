package io.github.basekt.auth.client

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Desktop and CLI OAuth 2.0 PKCE flow (RFC 8252 §7.3): binds an ephemeral loopback HTTP listener on `127.0.0.1`,
 * opens the authorization URL in the user's system browser via [openUrl], and receives the callback on [callbackPath]
 * using `java.base` sockets alone (no `jdk.httpserver` module required in `jlink` runtime images).
 *
 * Cancelling the calling coroutine immediately closes the loopback socket and releases the ephemeral port.
 *
 * @param openUrl host callback that opens the authorization URL in the system browser.
 * @property callbackPath HTTP path expected on `http://127.0.0.1:<port>` (`"/callback"` by default).
 * @property timeoutMillis maximum wait time in milliseconds before failing with [AuthFailureReason.OAuthCancelled]
 *   (`300_000` ms / 5 minutes by default).
 * @property successHeading heading rendered in the browser tab once an authorization code is received.
 * @property failureHeading heading rendered in the browser tab when consent is cancelled or fails.
 * @property detailMessage body paragraph rendered in the browser tab after callback completion.
 * @param randomBytes optional entropy source for deterministic testing.
 */
public class LoopbackOAuthFlow(
    private val openUrl: (String) -> Unit,
    public val callbackPath: String = DEFAULT_CALLBACK_PATH,
    public val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    public val successHeading: String = "Sign-in complete",
    public val failureHeading: String = "Sign-in was not completed",
    public val detailMessage: String = "You can close this browser tab and return to the application.",
    private val randomBytes: (() -> ByteArray)? = null,
) : OAuthRedirectFlow {

    init {
        require(callbackPath.startsWith("/")) { "callbackPath '$callbackPath' must start with '/'." }
        require(timeoutMillis > 0L) { "timeoutMillis ($timeoutMillis) must be positive." }
    }

    override suspend fun authorize(
        provider: String,
        rememberSession: Boolean,
        buildAuthorizeUrl: (redirectUri: String, challenge: PkceChallenge) -> String,
    ): AuthorizedCode = try {
        withTimeout(timeoutMillis) {
            coroutineScope {
                val serverSocket = withContext(Dispatchers.IO) {
                    ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply {
                        soTimeout = ACCEPT_POLL_MILLIS
                    }
                }
                val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { serverSocket.close() }
                    }
                }
                try {
                    val redirectUri = "http://127.0.0.1:${serverSocket.localPort}$callbackPath"
                    val challenge = if (randomBytes != null) PkceChallenge.generate(randomBytes) else PkceChallenge.generate()
                    openUrl(buildAuthorizeUrl(redirectUri, challenge))
                    when (val callback = awaitCallback(serverSocket)) {
                        is OAuthCallbackResult.Code -> AuthorizedCode(callback.code, challenge.verifier)
                        OAuthCallbackResult.Cancelled -> throw AuthClientException(
                            AuthFailureReason.OAuthCancelled,
                            "Sign-in was cancelled.",
                        )
                        is OAuthCallbackResult.Failed -> throw AuthClientException(
                            AuthFailureReason.ProviderError,
                            callback.message,
                            rawCode = callback.errorCode,
                        )
                    }
                } finally {
                    closer.cancel()
                    runCatching { serverSocket.close() }
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw AuthClientException(AuthFailureReason.OAuthCancelled, "Timed out waiting for browser sign-in.")
    }

    private suspend fun awaitCallback(serverSocket: ServerSocket): OAuthCallbackResult = withContext(Dispatchers.IO) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val client = try {
                serverSocket.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (closed: SocketException) {
                currentCoroutineContext().ensureActive()
                throw AuthClientException(
                    AuthFailureReason.OAuthCancelled,
                    "Loopback sign-in listener was closed.",
                    cause = closed,
                )
            }
            client.use { socket ->
                socket.soTimeout = SOCKET_READ_TIMEOUT_MILLIS
                val requestLine = runCatching {
                    BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
                }.getOrNull().orEmpty()
                val target = requestLine.split(' ').getOrNull(1).orEmpty()
                val path = target.substringBefore('?')
                if (path != callbackPath) {
                    writeHttp(socket.getOutputStream(), "404 Not Found", "text/plain; charset=utf-8", "Not Found")
                } else {
                    val query = target.substringAfter('?', missingDelimiterValue = "")
                    val parsed = OAuthCallbackParser.parseCallback(query)
                    if (parsed == null) {
                        writeHttp(
                            socket.getOutputStream(),
                            "400 Bad Request",
                            "text/plain; charset=utf-8",
                            "Missing OAuth parameters.",
                        )
                    } else {
                        val success = parsed is OAuthCallbackResult.Code
                        writeHttp(socket.getOutputStream(), "200 OK", "text/html; charset=utf-8", callbackHtml(success))
                        return@withContext parsed
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw CancellationException()
    }

    private fun writeHttp(output: OutputStream, status: String, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        runCatching {
            output.write(headers)
            output.write(bodyBytes)
            output.flush()
        }
    }

    private fun callbackHtml(success: Boolean): String {
        val heading = escapeHtml(if (success) successHeading else failureHeading)
        val detail = escapeHtml(detailMessage)
        return """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><title>$heading</title>
            <style>
            body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#111827;color:#f9fafb;font-family:system-ui,-apple-system,sans-serif}
            main{max-width:420px;padding:28px 32px;border:1px solid #374151;border-radius:10px;background:#1f2937;text-align:center}
            h1{margin:0 0 10px;font-size:20px;color:#f9fafb}
            p{margin:0;font-size:14px;color:#d1d5db;line-height:1.5}
            </style></head>
            <body><main><h1>$heading</h1><p>$detail</p></main></body></html>
        """.trimIndent()
    }

    private fun escapeHtml(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    /** Defaults for [LoopbackOAuthFlow]. */
    public companion object {
        /** Default loopback callback path (`"/callback"`). */
        public const val DEFAULT_CALLBACK_PATH: String = "/callback"

        /** Default loopback listener timeout (`300_000` ms / 5 minutes). */
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 5 * 60_000L

        private const val ACCEPT_POLL_MILLIS: Int = 500
        private const val SOCKET_READ_TIMEOUT_MILLIS: Int = 5_000
    }
}
