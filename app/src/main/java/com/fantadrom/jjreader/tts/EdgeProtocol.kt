package com.fantadrom.jjreader.tts

import org.json.JSONObject

/** Small independent formatter/parser. Service payloads never enter exported diagnostics. */
internal object EdgeProtocol {
    fun ssml(text: String, voice: Voice): String {
        val safe = StringBuilder()
        text.codePoints().forEach { point ->
            val cp = if (point == 9 || point == 10 || point == 13 || point in 0x20..0xd7ff || point in 0xe000..0xfffd || point in 0x10000..0x10ffff) point else 32
            when (cp) {
                38 -> safe.append("&amp;"); 60 -> safe.append("&lt;"); 62 -> safe.append("&gt;")
                34 -> safe.append("&quot;"); 39 -> safe.append("&apos;"); else -> safe.appendCodePoint(cp)
            }
        }
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${voice.locale}'><voice name='${voice.wireName}'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>$safe</prosody></voice></speak>"
    }
    fun headers(value: String): Map<String, String> = value.lineSequence().mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) null else line.take(colon).trim().lowercase(java.util.Locale.ROOT) to line.substring(colon + 1).trim()
    }.toMap()
    fun path(headers: Map<String, String>): String = headers["path"].let {
        if (it in setOf("turn.start", "response", "audio", "turn.end", "audio.metadata")) it!! else "other"
    }
    fun error(headers: Map<String, String>, body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val error = json?.opt("error")?.takeUnless { it == JSONObject.NULL || it == false || it == "" }
        val synthesis = json?.optJSONObject("context")?.optJSONObject("synthesis")
        val code = headers["x-errorcode"] ?: headers["error-code"] ?: (error as? JSONObject)?.optString("code")
            ?: json?.optString("errorCode")?.takeIf { it.isNotBlank() }
        val status = headers["status"] ?: synthesis?.optString("status") ?: json?.optString("status")
        val categories = mapOf("invalidargument" to "invalid_request", "badrequest" to "invalid_request",
            "unauthorized" to "authorization", "forbidden" to "authorization", "voicenotfound" to "voice_not_found",
            "serviceunavailable" to "service_unavailable", "toomanyrequests" to "rate_limited",
            "internalservererror" to "server_error", "error" to "server_error", "failed" to "server_error")
        val normalizedCode = code?.lowercase(java.util.Locale.ROOT)
        val normalizedStatus = status?.lowercase(java.util.Locale.ROOT)
        return categories[normalizedCode] ?: categories[normalizedStatus]
            ?: if (error != null || !code.isNullOrBlank()) "server_error_other" else null
    }
}
