package com.fantadrom.jjreader.tts

import com.fantadrom.edge.EdgeToken
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Voice(val wire: String, val label: String) {
    DMITRY("ru-RU-DmitryNeural", "Дмитрий"), SVETLANA("ru-RU-SvetlanaNeural", "Светлана")
}
class EdgeFailure(val code: String) : Exception(code)

/** Direct foreground-only Edge transport. No remote text logs, subscription keys or server. */
class EdgeClient {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).build()
    suspend fun synthesize(text: String, voice: Voice): ByteArray = withTimeout(30000) {
        require(text.isNotBlank() && text.length <= 4000)
        suspendCancellableCoroutine { continuation ->
            val id = UUID.randomUUID().toString().replace("-", "")
            val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=${EdgeToken.CLIENT}&ConnectionId=$id" +
                "&Sec-MS-GEC=${EdgeToken.generate(Instant.now().epochSecond)}&Sec-MS-GEC-Version=2-143.0.3650.75"
            val request = Request.Builder().url(url)
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)};")
                .build()
            val bytes = ByteArrayOutputStream()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                private fun fail(code: String, ws: WebSocket) {
                    ws.cancel()
                    if (continuation.isActive) continuation.resumeWithException(EdgeFailure(code))
                }
                override fun onOpen(ws: WebSocket, response: Response) {
                    val timestamp = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).withZone(ZoneOffset.UTC).format(Instant.now())
                    ws.send("X-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                    val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
                        .filter { it >= ' ' || it == '\n' || it == '\t' }
                    ws.send("X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp}Z\r\nPath:ssml\r\n\r\n" +
                        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ru-RU'><voice name='${voice.wire}'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>$escaped</prosody></voice></speak>")
                }
                override fun onMessage(ws: WebSocket, data: ByteString) {
                    if (data.size < 2) return fail("FRAME_SHORT", ws)
                    val length = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
                    if (length + 2 > data.size) return fail("FRAME_HEADER", ws)
                    val header = data.substring(2, 2 + length).utf8()
                    if (header.lineSequence().any { it.trim() == "Path:audio" }) {
                        if (bytes.size() + data.size > 2 * 1024 * 1024) return fail("AUDIO_TOO_LARGE", ws)
                        bytes.write(data.substring(2 + length).toByteArray())
                    }
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    if (text.substringBefore("\r\n\r\n").lineSequence().any { it.trim() == "Path:turn.end" }) {
                        val audio = bytes.toByteArray()
                        if (audio.size < 100) return fail("NO_AUDIO", ws)
                        if (continuation.isActive) continuation.resume(audio)
                        ws.close(1000, null)
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) = fail(response?.let { "HTTP_${it.code}" } ?: "NETWORK", ws)
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { if (continuation.isActive) fail("CLOSED_$code", ws) }
            })
            continuation.invokeOnCancellation { socket.cancel() }
        }
    }
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
