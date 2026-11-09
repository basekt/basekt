package io.github.basekt.auth.client

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class LoopbackOAuthFlowTest {

    @Test
    fun `loopback listener ignores unrelated paths accepts callback code and escapes HTML`() = runBlocking {
        val openedUrl = CompletableDeferred<String>()
        val flow = LoopbackOAuthFlow(
            openUrl = { openedUrl.complete(it) },
            successHeading = "Signed in <Safely>",
        )

        val pending = async {
            flow.authorize(OAuthProviders.GOOGLE, rememberSession = true) { redirectUri, challenge ->
                "https://project.supabase.co/auth/v1/authorize?redirect_to=$redirectUri&code_challenge=${challenge.challenge}"
            }
        }

        val authorizeUrl = openedUrl.await()
        val redirectParam = URI(authorizeUrl).rawQuery.split('&')
            .first { it.startsWith("redirect_to=") }
            .substringAfter('=')
            .let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        val callbackUri = URI(redirectParam)

        val faviconReply = rawHttpGet(callbackUri.port, "/favicon.ico")
        assertTrue(faviconReply.startsWith("HTTP/1.1 404"))

        val missingReply = rawHttpGet(callbackUri.port, "/callback")
        assertTrue(missingReply.startsWith("HTTP/1.1 400"))

        val callbackReply = rawHttpGet(callbackUri.port, "/callback?code=loopback-code-321")
        assertTrue(callbackReply.startsWith("HTTP/1.1 200"))
        assertTrue("Signed in &lt;Safely&gt;" in callbackReply)

        val authorized = pending.await()
        assertEquals("loopback-code-321", authorized.code)
        assertEquals(48, authorized.codeVerifier.length)
    }

    @Test
    fun `cancelling loopback flow closes ephemeral port immediately`() = runBlocking {
        val openedUrl = CompletableDeferred<String>()
        val flow = LoopbackOAuthFlow(openUrl = { openedUrl.complete(it) })

        val job = launch {
            flow.authorize(OAuthProviders.GOOGLE, rememberSession = false) { redirectUri, _ -> redirectUri }
        }
        val callbackUri = URI(openedUrl.await())
        job.cancel()
        job.join()

        val portStillOpen = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", callbackUri.port), 250) }
                true
            }.getOrDefault(false)
        }
        assertFalse(portStillOpen, "Loopback socket must close as soon as the coroutine is cancelled")

        // Cancelled consent in browser maps to OAuthCancelled.
        val cancelUrl = CompletableDeferred<String>()
        val cancelFlow = LoopbackOAuthFlow(openUrl = { cancelUrl.complete(it) })
        val cancelledDeferred = async {
            assertFailsWith<AuthClientException> {
                cancelFlow.authorize(OAuthProviders.GOOGLE, rememberSession = false) { redirectUri, _ -> redirectUri }
            }
        }
        val cancelPort = URI(cancelUrl.await()).port
        rawHttpGet(cancelPort, "/callback?error=access_denied")
        assertEquals(AuthFailureReason.OAuthCancelled, cancelledDeferred.await().reason)
    }

    private suspend fun rawHttpGet(port: Int, pathAndQuery: String): String = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply {
                write("GET $pathAndQuery HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            socket.getInputStream().bufferedReader().readText()
        }
    }
}
