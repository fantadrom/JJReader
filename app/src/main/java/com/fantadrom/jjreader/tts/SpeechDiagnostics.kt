package com.fantadrom.jjreader.tts

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Allow-listed fields only. Never store exception messages, URLs, request bodies or headers. */
class SpeechDiagnostics(private val persist: (String) -> Unit = {}) {
    private val attempts = linkedMapOf<String, JSONObject>()
    private var generation = 0L
    private var closed = false
    @Synchronized fun beginRun(value: Long) { generation = value; attempts.clear(); publish() }
    @Synchronized fun continueRun(value: Long) { generation = value; publish() }
    @Synchronized fun attempt(voice: Voice, source: String = "pending"): Attempt {
        val id = UUID.randomUUID().toString()
        val now = System.nanoTime()
        attempts[id] = JSONObject().put("id", id).put("generation", generation).put("voice", voice.wire)
            .put("timeUtc", Instant.now().toString()).put("stage", "cache").put("source", source)
        while (attempts.size > 12) attempts.remove(attempts.keys.first())
        publish()
        return Attempt(this, id, now)
    }
    @Synchronized internal fun update(id: String, began: Long, stage: String, source: String? = null,
                                      http: Int? = null, close: Int? = null, error: Throwable? = null, code: String? = null) {
        if (closed) return
        val row = attempts[id] ?: return // A previous generation cannot contaminate a new run.
        row.put("stage", stage).put("durationMs", (System.nanoTime() - began) / 1_000_000)
        source?.let { row.put("source", it) }; http?.let { row.put("httpStatus", it) }
        close?.let { row.put("webSocketCloseCode", it) }; code?.let { row.put("code", it) }
        error?.let {
            row.put("exception", it.javaClass.simpleName)
            row.put("causes", JSONArray(generateSequence(it.cause) { t -> t.cause }.take(4).map { t -> t.javaClass.simpleName }.toList()))
        }
        publish()
    }
    @Synchronized fun text(): String = JSONObject().put("schema", 1).put("generation", generation)
        .put("attempts", JSONArray(attempts.values.toList())).toString(2)
    private fun publish() { persist(text()) }
    @Synchronized fun close() { closed = true }
    class Attempt internal constructor(private val owner: SpeechDiagnostics, val id: String, private val began: Long) {
        @Volatile var stage: String = "cache"; private set
        fun record(stage: String, source: String? = null, http: Int? = null, close: Int? = null, error: Throwable? = null, code: String? = null) {
            this.stage = stage; owner.update(id, began, stage, source, http, close, error, code)
        }
    }
}
