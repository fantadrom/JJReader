package com.fantadrom.jjreader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** P0: up to five phrases in one resource, one prefetched phrase, explicit resume. */
class PhrasePlayer(private val context: Context, private val scope: CoroutineScope,
                   private val onPhrase: suspend (Phrase) -> Unit, private val status: (String) -> Unit) {
    private val edge = EdgeClient()
    private val directory = File(context.cacheDir, "tts-p0").apply { mkdirs() }
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) pause() }.build()
    private var job: Job? = null
    private var media: MediaPlayer? = null
    var active = false; private set
    var cursor = 0; private set
    private var phrases: List<Phrase> = emptyList()
    private var voice = Voice.DMITRY
    private var generation = 0L
    fun start(items: List<Phrase>, selected: Voice) {
        pause()
        phrases = items.take(5); cursor = 0; voice = selected
        resume()
    }
    fun resume() {
        if (phrases.isEmpty() || cursor >= phrases.size || active ||
            (context as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { status("Нет аудиофокуса"); return }
        active = true
        val run = generation
        val runVoice = voice
        job = scope.launch {
            try {
                coroutineScope {
                    var ready = async(Dispatchers.IO) { audio(phrases[cursor], runVoice) }
                    while (cursor < phrases.size) {
                        status("Озвучка: ${voice.label}, фраза ${cursor + 1}/${phrases.size}")
                        val file = ready.await()
                        val nextPhrase = phrases.getOrNull(cursor + 1)
                        val next = nextPhrase?.let { p -> async(Dispatchers.IO) { audio(p, runVoice) } }
                        ensureActive()
                        if (generation != run) return@coroutineScope
                        onPhrase(phrases[cursor])
                        play(file)
                        cursor++
                        if (next != null) ready = next
                    }
                }
                status("Прочитаны пробные фразы")
            } catch (e: TimeoutCancellationException) { if (generation == run) status("Edge TTS: TIMEOUT. Повтор — вручную.") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == run) status("Edge TTS: ${if (e is EdgeFailure) e.code else "AUDIO_OR_NETWORK"}. Повтор — вручную.") }
            finally { if (generation == run) { active = false; media?.release(); media = null; manager.abandonAudioFocusRequest(focus) } }
        }
    }
    private suspend fun audio(phrase: Phrase, runVoice: Voice): File {
        val file = File(directory, Phrases.cacheKey(phrase.text, runVoice) + ".mp3")
        if (file.exists() && file.length() > 100) return file
        val bytes = edge.synthesize(phrase.text, runVoice)
        currentCoroutineContext().ensureActive()
        val temporary = File(directory, "${file.name}.${java.util.UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(bytes)
            currentCoroutineContext().ensureActive()
            if (!file.exists()) check(temporary.renameTo(file))
        }
        finally { temporary.delete() }
        directory.listFiles()?.filter { it.extension == "mp3" && it != file }?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        return file
    }
    private suspend fun play(file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        val player = MediaPlayer(); media = player
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener { if (continuation.isActive) it.start() }
        player.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
        player.setOnErrorListener { _, _, _ -> if (continuation.isActive) continuation.resumeWithException(EdgeFailure("AUDIO_DECODE")); true }
        player.setDataSource(file.absolutePath); player.prepareAsync()
        continuation.invokeOnCancellation { player.setOnPreparedListener(null); player.setOnCompletionListener(null); player.setOnErrorListener(null) }
    }
    fun pause() {
        generation++
        job?.cancel(); job = null
        media?.release(); media = null
        active = false; manager.abandonAudioFocusRequest(focus)
        status("Пауза. Продолжение — вручную, с начала фразы.")
    }
    fun reset() { pause(); phrases = emptyList(); cursor = 0 }
    fun close() { pause(); edge.close() }
}
