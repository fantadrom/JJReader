package com.fantadrom.jjreader.tts

import com.fantadrom.edge.EdgeToken
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.net.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Voice(val wire: String, val label: String) {
    DMITRY("ru-RU-DmitryNeural", "Дмитрий"), SVETLANA("ru-RU-SvetlanaNeural", "Светлана")
}
class EdgeFailure(val code: String, val stage: String = "receive", cause: Throwable? = null, val retryAfterMs: Long? = null) : Exception(code, cause)

/** The endpoint, protocol token and synthesis parameters are unchanged from the voices heard on Honor. */
class EdgeClient(private val endpoint: String = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1",
                 baseClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()) {
    private val client = baseClient.newBuilder().eventListenerFactory { call ->
        val attempt = call.request().tag(SpeechDiagnostics.Attempt::class.java)
        object : EventListener() {
            override fun dnsStart(call: Call, domainName: String) { attempt?.record("DNS", source = "network") }
            override fun connectStart(call: Call, address: InetSocketAddress, proxy: Proxy) { attempt?.record("connect") }
            override fun secureConnectStart(call: Call) { attempt?.record("TLS") }
            override fun responseHeadersEnd(call: Call, response: Response) { attempt?.record("handshake", http = response.code) }
        }
    }.build()
    suspend fun synthesize(text: String, voice: Voice, attempt: SpeechDiagnostics.Attempt? = null): ByteArray {
        require(text.isNotBlank() && text.length <= 4000)
        try {
            return withTimeout(30000) {
                suspendCancellableCoroutine { continuation ->
                    val done = AtomicBoolean(false)
                    val id = UUID.randomUUID().toString().replace("-", "")
                    val url = "$endpoint?TrustedClientToken=${EdgeToken.CLIENT}&ConnectionId=$id" +
                        "&Sec-MS-GEC=${EdgeToken.generate(Instant.now().epochSecond)}&Sec-MS-GEC-Version=2-143.0.3650.75"
                    val request = Request.Builder().url(url)
                        .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                        .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)};")
                        .tag(SpeechDiagnostics.Attempt::class.java, attempt).build()
                    val bytes = ByteArrayOutputStream()
                    attempt?.record("connect", source = "network")
                    // OkHttp's WebSocket implementation can disable Call EventListener callbacks.
                    // A delegating DNS hook records resolution without changing the resolver.
                    val requestClient = client.newBuilder().dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> {
                            attempt?.record("DNS", source = "network")
                            return client.dns.lookup(hostname).also { attempt?.record("connect") }
                        }
                    }).build()
                    val socket = requestClient.newWebSocket(request, object : WebSocketListener() {
                        private fun fail(code: String, ws: WebSocket, error: Throwable? = null, http: Int? = null, close: Int? = null, retryAfter: Long? = null) {
                            if (!done.compareAndSet(false, true)) return
                            val stage = attempt?.stage ?: "receive"
                            attempt?.record(stage, http = http, close = close, error = error, code = code)
                            continuation.resumeWithException(EdgeFailure(code, stage, error, retryAfter))
                            ws.cancel()
                        }
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (done.get()) { webSocket.cancel(); return }
                            attempt?.record("opened", http = response.code)
                            val timestamp = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).withZone(ZoneOffset.UTC).format(Instant.now())
                            val configSent = webSocket.send("X-Timestamp:$timestamp\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                                "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
                            val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
                                .filter { it >= ' ' || it == '\n' || it == '\t' }
                            val ssmlSent = webSocket.send("X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp}Z\r\nPath:ssml\r\n\r\n" +
                                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ru-RU'><voice name='${voice.wire}'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>$escaped</prosody></voice></speak>")
                            if (!configSent || !ssmlSent) fail("SEND_REJECTED", webSocket)
                        }
                        override fun onMessage(webSocket: WebSocket, bytesMessage: ByteString) {
                            if (done.get()) return
                            if (bytesMessage.size < 2) return fail("FRAME_SHORT", webSocket)
                            val length = ((bytesMessage[0].toInt() and 255) shl 8) or (bytesMessage[1].toInt() and 255)
                            if (length + 2 > bytesMessage.size) return fail("FRAME_HEADER", webSocket)
                            val header = bytesMessage.substring(2, 2 + length).utf8()
                            if (header.lineSequence().any { it.trim() == "Path:audio" }) {
                                if (bytes.size() + bytesMessage.size > 2 * 1024 * 1024) return fail("AUDIO_TOO_LARGE", webSocket)
                                val chunk = bytesMessage.substring(2 + length).toByteArray()
                                if (chunk.isNotEmpty()) {
                                    if (bytes.size() == 0) attempt?.record("firstAudio")
                                    bytes.write(chunk); attempt?.audio(chunk.size)
                                }
                            }
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (done.get()) return
                            if (text.substringBefore("\r\n\r\n").lineSequence().any { it.trim() == "Path:turn.end" }) {
                                val audio = bytes.toByteArray()
                                if (audio.size < 100) return fail("NO_AUDIO", webSocket)
                                if (done.compareAndSet(false, true)) {
                                    attempt?.record("synthesized")
                                    continuation.resume(audio)
                                    webSocket.close(1000, null)
                                }
                            }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (done.get()) return
                            when {
                                response != null -> attempt?.record("handshake", http = response.code)
                                t is UnknownHostException -> attempt?.record("DNS")
                                t is SSLException -> attempt?.record("TLS")
                            }
                            val code = when {
                                response != null -> "HTTP_${response.code}"
                                t is UnknownHostException -> "DNS"
                                t is SocketTimeoutException -> "TIMEOUT"
                                t is SSLException -> "TLS"
                                t is ConnectException -> "CONNECT"
                                else -> "IO_${t.javaClass.simpleName}"
                            }
                            val retryAfter = response?.header("Retry-After")?.let { raw ->
                                raw.toLongOrNull()?.coerceIn(0, 86400)?.times(1000) ?: runCatching {
                                    (java.time.ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
                                }.getOrNull()
                            }
                            fail(code, webSocket, t, http = response?.code, retryAfter = retryAfter)
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            if (!done.get()) fail("CLOSED_$code", webSocket, close = code) else webSocket.close(code, null)
                        }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (!done.get()) fail("CLOSED_$code", webSocket, close = code)
                            else attempt?.let { it.record(it.stage, close = code) }
                        }
                    })
                    continuation.invokeOnCancellation {
                        if (done.compareAndSet(false, true)) attempt?.record("cancelled", code = "CANCELLED")
                        socket.cancel()
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            attempt?.record("receive", error = e, code = "TIMEOUT")
            throw EdgeFailure("TIMEOUT", "receive", e)
        }
    }
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
