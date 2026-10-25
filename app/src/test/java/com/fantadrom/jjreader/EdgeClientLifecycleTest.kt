package com.fantadrom.jjreader

import com.fantadrom.jjreader.tts.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EdgeClientLifecycleTest {
    private fun success() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.startsWith("X-RequestId:")) {
                val header = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
                val frame = byteArrayOf((header.size ushr 8).toByte(), header.size.toByte()) + header + ByteArray(200) { 1 }
                webSocket.send(ByteString.of(*frame)); webSocket.send("Path:turn.end\r\n\r\n")
                webSocket.send("Path:turn.end\r\n\r\n") // Duplicate terminal frame must not double-resume.
            }
        }
    })
    @Test fun repeatedSuccessHttpFailureAndExplicitRetryReuseClient() = runBlocking {
        val server = MockWebServer(); server.start()
        val diagnostics = SpeechDiagnostics()
        val client = EdgeClient(server.url("/edge").toString())
        try {
            server.enqueue(success()); server.enqueue(success()); server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(success())
            repeat(2) { assertEquals(200, client.synthesize("Синтетическая фраза.", Voice.DMITRY, diagnostics.attempt(Voice.DMITRY)).size) }
            try { client.synthesize("Синтетическая фраза.", Voice.SVETLANA, diagnostics.attempt(Voice.SVETLANA)); fail("Expected HTTP failure") }
            catch (e: EdgeFailure) { assertEquals("HTTP_503", e.code); assertEquals("handshake", e.stage) }
            assertEquals(200, client.synthesize("Синтетическая фраза.", Voice.SVETLANA, diagnostics.attempt(Voice.SVETLANA)).size)
            assertEquals(4, server.requestCount)
            assertTrue(diagnostics.text().contains("503"))
            assertFalse(diagnostics.text().contains("Синтетическая"))
        } finally { client.close(); server.shutdown() }
    }
    @Test fun cancellationOfAWebSocketDoesNotCloseReusableClient() = runBlocking {
        val server = MockWebServer(); server.start(); val opened = CompletableDeferred<Unit>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        server.enqueue(success())
        val client = EdgeClient(server.url("/edge").toString())
        try {
            val pending = async { client.synthesize("Пауза.", Voice.DMITRY) }
            withTimeout(5000) { opened.await() }; pending.cancelAndJoin()
            assertEquals(200, client.synthesize("После паузы.", Voice.DMITRY).size)
        } finally { client.close(); server.shutdown() }
    }
}
