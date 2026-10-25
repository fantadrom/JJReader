package com.fantadrom.jjreader.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Bounded P0 cache, serialized commits and cancellation before publication of an entry. */
class PhraseAudioCache(private val directory: File,
                       private val onRetry: suspend (Int) -> Unit = {},
                       private val synthesize: suspend (String, Voice, SpeechDiagnostics.Attempt) -> ByteArray) {
    private val mutex = Mutex()
    suspend fun get(phrase: Phrase, voice: Voice, attempt: SpeechDiagnostics.Attempt): File = mutex.withLock {
        directory.mkdirs()
        val file = File(directory, Phrases.cacheKey(phrase.text, voice) + ".mp3")
        if (file.exists() && file.length() > 100) { file.setLastModified(System.currentTimeMillis()); attempt.record("cache", source = "cache"); return@withLock file }
        var bytes: ByteArray? = null
        for (number in 1..3) {
            currentCoroutineContext().ensureActive()
            val network = if (number == 1) attempt else attempt.retry(number)
            try {
                network.record("connect", source = "network")
                bytes = synthesize(phrase.text, voice, network)
                break
            } catch (e: CancellationException) { network.record("cancelled", code = "CANCELLED"); throw e }
            catch (e: EdgeFailure) {
                network.record(e.stage, error = e, code = e.code)
                val wait = retryDelay(e, number) ?: throw e
                onRetry(number + 1)
                delay(wait)
            }
        }
        currentCoroutineContext().ensureActive()
        val temporary = File(directory, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(checkNotNull(bytes)); currentCoroutineContext().ensureActive()
            check(temporary.renameTo(file))
        } finally { temporary.delete() }
        directory.listFiles()?.filter { it.extension == "mp3" && it != file }?.sortedByDescending { it.lastModified() }?.drop(11)?.forEach { it.delete() }
        file
    }
    companion object {
        /** Only transport failures and transient HTTP responses. No protocol/frame/auth/TLS retry. */
        internal fun retryDelay(error: EdgeFailure, number: Int): Long? {
            if (number >= 3) return null
            val retryable = error.code in setOf("DNS", "CONNECT", "TIMEOUT", "IO_SocketException", "IO_EOFException", "HTTP_408", "HTTP_429", "HTTP_502", "HTTP_503", "HTTP_504")
            if (!retryable) return null
            val base = if (number == 1) 500L else 1500L
            // Respect a long server instruction by stopping, never retry earlier than Retry-After.
            val requested = error.retryAfterMs ?: 0
            if (requested > 5000) return null
            return maxOf(base, requested)
        }
    }
}
