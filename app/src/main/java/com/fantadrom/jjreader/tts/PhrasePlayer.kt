package com.fantadrom.jjreader.tts

import android.content.Context
import android.media.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.fantadrom.jjreader.books.BookStore
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android audio/focus and bounded cache for SpeechQueue. No background service or WakeLock. */
class PhrasePlayer(context: Context, private val scope: CoroutineScope,
                   onPhrase: suspend (Phrase?) -> Unit, private val status: (String) -> Unit) {
    private val edge = EdgeClient()
    private val alternativeEdge = EdgeClient(route=EdgeRoute.READEST)
    private val directory = File(context.cacheDir, "tts-p0").apply { mkdirs() }
    private val diagnosticFile = File(context.filesDir, "tts-last-attempt.json")
    private val diagnostics = SpeechDiagnostics { json ->
        try { synchronized(diagnosticFile.absolutePath.intern()) { BookStore.writeAtomic(diagnosticFile, org.json.JSONObject(json)) } } catch (_: Exception) { }
    }
    private val cache = PhraseAudioCache(directory, onRetry = { number ->
        withContext(Dispatchers.Main) { ensureActive(); status("Повтор подключения $number/3") }
    }) { text, voice, attempt -> edge.synthesize(text, voice, attempt) }
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) pause() }.build()
    private var media: MediaPlayer? = null
    private val queue: SpeechQueue = SpeechQueue(scope, diagnostics, ::audio, ::play, onPhrase, status,
        canPlay = { (context as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true },
        acquire = { manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }, stop = {
            media?.release(); media = null; manager.abandonAudioFocusRequest(focus)
        })
    private var probeJob: Job? = null
    private var probeGeneration = 0L
    private val canProbe = { (context as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true }
    val active get() = queue.active || probeJob?.isActive == true
    fun checkVoices(compareRoutes: Boolean = false) {
        reset()
        if (!canProbe() || manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status("Проверка доступна в приложении при наличии аудиофокуса"); return
        }
        val run = --probeGeneration
        probeJob = scope.launch {
            try {
                suspend fun fetch(client: EdgeClient, text: String, voice: Voice, attempt: SpeechDiagnostics.Attempt): File {
                    val file = File.createTempFile("voice-probe-", ".mp3", directory)
                    try {
                        withContext(Dispatchers.IO) { val audio = client.synthesize(text, voice, attempt); ensureActive(); file.writeBytes(audio); ensureActive() }
                        return file
                    } catch (e: Exception) { file.delete(); throw e }
                }
                VoiceProbe(diagnostics, { text, voice, attempt -> fetch(edge,text,voice,attempt) },
                    { file, attempt -> play(file, attempt) {} }, status,
                    { text, voice, attempt -> fetch(alternativeEdge,text,voice,attempt) }).run(run,compareRoutes)
            } finally {
                if (run == probeGeneration) { media?.release(); media = null; manager.abandonAudioFocusRequest(focus); probeJob = null }
            }
        }
    }
    private fun cancelProbe() {
        val old = probeJob ?: return
        probeGeneration--; probeJob = null; old.cancel()
        media?.release(); media = null; manager.abandonAudioFocusRequest(focus)
    }

    fun start(items: List<Phrase>, selected: Voice) { cancelProbe(); queue.start(items, selected) }
    fun resume() { cancelProbe(); queue.resume() }
    fun pause() { cancelProbe(); queue.pause() }
    fun reset() { cancelProbe(); queue.reset() }
    fun diagnosticText(): String = if (diagnostics.text().contains("\"id\"")) diagnostics.text() else
        try { BookStore.readAtomic(diagnosticFile).toString(2) } catch (_: Exception) { "Попыток озвучки пока нет" }
    private suspend fun audio(phrase: Phrase, voice: Voice, attempt: SpeechDiagnostics.Attempt): File {
        return cache.get(phrase, voice, attempt)
    }
    private suspend fun play(file: File, attempt: SpeechDiagnostics.Attempt, onStarted: suspend () -> Unit) {
        attempt.record("decode")
        var owned: MediaPlayer? = null
        try {
            withTimeout(maxOf(30000L, file.length() * 1000 / 6000 + 15000).coerceAtMost(180000)) {
                val playbackScope = this
                suspendCancellableCoroutine<Unit> { continuation ->
                    val player = MediaPlayer(); owned = player; media = player
                    player.setAudioAttributes(attributes)
                    player.setOnPreparedListener {
                        playbackScope.launch {
                            try {
                                if (!continuation.isActive) return@launch
                                onStarted()
                                if (continuation.isActive && media === player) { player.start(); attempt.record("playback") }
                            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                        }
                    }
                    player.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                    player.setOnErrorListener { _, _, _ ->
                        file.delete(); attempt.record("decode", code = "AUDIO_DECODE")
                        if (continuation.isActive) continuation.resumeWithException(EdgeFailure("AUDIO_DECODE", "decode")); true
                    }
                    try { player.setDataSource(file.absolutePath); player.prepareAsync() }
                    catch (e: Exception) { file.delete(); if (continuation.isActive) continuation.resumeWithException(EdgeFailure("AUDIO_DECODE", "decode", e)) }
                    continuation.invokeOnCancellation { player.setOnPreparedListener(null); player.setOnCompletionListener(null); player.setOnErrorListener(null) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            attempt.record("decode", error = e, code = "AUDIO_TIMEOUT")
            throw EdgeFailure("AUDIO_TIMEOUT", "decode", e)
        } finally {
            owned?.let { if (media === it) { media = null; it.release() } }
        }
    }
    fun close() { cancelProbe(); queue.close(); diagnostics.close(); edge.close(); alternativeEdge.close() }
}
