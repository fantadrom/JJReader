package com.fantadrom.jjreader.tts

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Allow-listed fields only. Never store exception messages, URLs, request bodies or headers. */
class SpeechDiagnostics(private val persist: (String) -> Unit = {}) {
    private val attempts = linkedMapOf<String, JSONObject>()
    private var generation = 0L
    private var sessionId = UUID.randomUUID().toString()
    private var closed = false
    private var purpose = "reading"
    @Synchronized fun beginRun(value: Long, mode: String = "reading") { purpose = mode; generation = value; sessionId = UUID.randomUUID().toString(); attempts.clear(); publish() }
    @Synchronized fun continueRun(value: Long) { generation = value; publish() }
    @Synchronized fun attempt(voice: Voice, source: String = "pending", block: Int = 0, number: Int = 1, maxAttempts: Int = 3): Attempt {
        val id = UUID.randomUUID().toString()
        val now = System.nanoTime()
        attempts[id] = JSONObject().put("id", id).put("generation", generation).put("voice", voice.wire)
            .put("audioBytes", 0).put("audioFrames", 0).put("block", block).put("networkAttempt", if (source == "cache") 0 else number).put("maxAttempts", maxAttempts).put("textMessages", 0).put("binaryMessages", 0).put("paths", JSONObject()).put("protocolError", false)
            .put("timeUtc", Instant.now().toString()).put("stage", "cache").put("source", source)
        while (attempts.size > 32) attempts.remove(attempts.keys.first())
        publish()
        return Attempt(this, id, now, voice, block, number, generation, sessionId)
    }
    @Synchronized internal fun update(id: String, began: Long, stage: String, source: String? = null,
                                      http: Int? = null, close: Int? = null, error: Throwable? = null, code: String? = null) {
        if (closed) return
        val row = attempts[id] ?: return // A previous generation cannot contaminate a new run.
        if (row.optString("outcome") in setOf("error", "cancelled") && code == null) {
            close?.let { row.put("webSocketCloseCode", it); publish() }
            return
        }
        // Late WebSocket close carries metadata only; never moves a terminal stage backwards.
        if (close == null || code != null) row.put("stage", stage)
        if (stage == "cache" && source == "cache") row.put("networkAttempt", 0).put("synthesis", "cached")
        val elapsed = (System.nanoTime() - began) / 1_000_000
        when (stage) {
            "opened", "firstAudio", "synthesized", "playbackReady", "playback", "completed" -> if (!row.has(stage + "Ms")) row.put(stage + "Ms", elapsed)
        }
        if (stage == "synthesized") row.put("synthesis", "success")
        if (stage == "playback") row.put("playback", "started")
        if (stage == "completed") row.put("playback", "completed")
        if (code != null) row.put("outcome", if (code == "CANCELLED") "cancelled" else "error")
        if (stage == "synthesized" || stage == "completed") row.put("outcome", "success")
        row.put("durationMs", (System.nanoTime() - began) / 1_000_000)
        source?.let { row.put("source", it) }; http?.let { row.put("httpStatus", it) }
        close?.let { row.put("webSocketCloseCode", it) }; code?.let { row.put("code", it) }
        error?.let {
            val chain = generateSequence(it) { t -> t.cause }.take(5).toList()
            val category = when {
                chain.any { t -> t is java.net.SocketTimeoutException } -> "timeout"
                chain.any { t -> t is java.net.SocketException } -> "socket_error"
                chain.any { t -> t is java.io.EOFException } -> "stream_closed"
                else -> null
            }
            category?.let { c -> row.put("category", c) }
            row.put("exception", it.javaClass.simpleName)
            row.put("causes", JSONArray(generateSequence(it.cause) { t -> t.cause }.take(4).map { t -> t.javaClass.simpleName }.toList()))
        }
        publish()
    }
    @Synchronized fun text(): String = JSONObject().put("schema", 4).put("purpose", purpose).put("sessionId", sessionId).put("generation", generation)
        .put("attempts", JSONArray(attempts.values.toList())).toString(2)
    private fun publish() { persist(text()) }
    @Synchronized fun close() { closed = true }
    @Synchronized internal fun audio(id: String, bytes: Int) {
        if (closed) return
        val row = attempts[id] ?: return
        row.put("audioBytes", row.optInt("audioBytes") + bytes).put("audioFrames", row.optInt("audioFrames") + 1)
        publish()
    }
    @Synchronized internal fun protocol(id: String, field: String, value: Any) {
        if (closed) return
        val row = attempts[id] ?: return
        when (field) {
            "textMessages", "binaryMessages" -> row.put(field, row.optInt(field) + 1)
            "path" -> row.getJSONObject("paths").let { it.put(value.toString(), it.optInt(value.toString()) + 1) }
            else -> row.put(field, value)
        }
        publish()
    }
    @Synchronized internal fun retry(voice: Voice, block: Int, number: Int, run: Long, session: String): Attempt {
        if (closed || run != generation || session != sessionId)
            return Attempt(this, UUID.randomUUID().toString(), System.nanoTime(), voice, block, number, run, session)
        return attempt(voice, "network", block, number)
    }
    class Attempt internal constructor(private val owner: SpeechDiagnostics, val id: String, private val began: Long,
                                       private val voice: Voice, private val block: Int, val number: Int, private val run: Long, private val session: String) {
        var latest: Attempt = this; private set
        fun retry(number: Int): Attempt = owner.retry(voice, block, number, run, session).also { latest = it }
        fun configuration(route: String) {
            owner.protocol(id,"route",route);owner.protocol(id,"ssmlProfile","full-ru-default-v1")
            owner.protocol(id,"clientConfiguration","jj-p0.5-r1")
        }
        internal fun response(response: EdgeProtocol.ResponseSummary) {
            owner.protocol(id,"responseFormat",response.format)
            owner.protocol(id,"responseStatus",response.status)
            owner.protocol(id,"responseHasStatus",response.hasStatus)
            owner.protocol(id,"responseHasError",response.hasError)
        }
        fun wireName() = owner.protocol(id, "wireNameKind", "full")
        fun sent(config: Boolean, ssml: Boolean) {
            owner.protocol(id, "configSendAccepted", config); owner.protocol(id, "ssmlSendAccepted", ssml)
        }
        fun message(binary: Boolean) = owner.protocol(id, if (binary) "binaryMessages" else "textMessages", 1)
        fun path(value: String) = owner.protocol(id, "path", value)
        fun protocolError(category: String) { owner.protocol(id, "protocolError", true); owner.protocol(id, "protocolCategory", category) }
        fun audio(bytes: Int) { owner.audio(id, bytes) }
        @Volatile var stage: String = "cache"; private set
        fun record(stage: String, source: String? = null, http: Int? = null, close: Int? = null, error: Throwable? = null, code: String? = null) {
            this.stage = stage; owner.update(id, began, stage, source, http, close, error, code)
        }
    }
}
