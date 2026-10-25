package com.fantadrom.jjreader.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** User-triggered control sample: sequential voices, one request each, no cache/prefetch/retry. */
internal class VoiceProbe(private val diagnostics: SpeechDiagnostics,
                          private val fetch: suspend (String, Voice, SpeechDiagnostics.Attempt) -> File,
                          private val play: suspend (File, SpeechDiagnostics.Attempt) -> Unit,
                          private val status: (String) -> Unit) {
    suspend fun run(generation: Long) {
        diagnostics.beginRun(generation, "voiceCheck")
        for ((index, voice) in Voice.entries.withIndex()) {
            currentCoroutineContext().ensureActive()
            val attempt = diagnostics.attempt(voice, "network", block = index + 1, maxAttempts = 1)
            var file: File? = null
            try {
                status("Проверка голоса: ${voice.label}")
                file = fetch(TEXT, voice, attempt)
                currentCoroutineContext().ensureActive()
                play(file, attempt)
                currentCoroutineContext().ensureActive()
                attempt.record("completed")
            } catch (e: CancellationException) { attempt.record("cancelled", code = "CANCELLED"); throw e }
            catch (e: Exception) { attempt.record(if (e is EdgeFailure) e.stage else "playback", error = e, code = if (e is EdgeFailure) e.code else "PLAYBACK") }
            finally { file?.delete() }
        }
        status("Проверка голосов завершена. Результаты — в диагностике.")
    }
    companion object { const val TEXT = "Проверка голоса. Сегодня ясный день." }
}
