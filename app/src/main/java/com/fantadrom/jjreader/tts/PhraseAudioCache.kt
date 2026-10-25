package com.fantadrom.jjreader.tts

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Bounded P0 cache, serialized commits and cancellation before publication of an entry. */
class PhraseAudioCache(private val directory: File,
                       private val synthesize: suspend (String, Voice, SpeechDiagnostics.Attempt) -> ByteArray) {
    private val mutex = Mutex()
    suspend fun get(phrase: Phrase, voice: Voice, attempt: SpeechDiagnostics.Attempt): File = mutex.withLock {
        directory.mkdirs()
        val file = File(directory, Phrases.cacheKey(phrase.text, voice) + ".mp3")
        if (file.exists() && file.length() > 100) { file.setLastModified(System.currentTimeMillis()); attempt.record("cache", source = "cache"); return@withLock file }
        val bytes = synthesize(phrase.text, voice, attempt)
        currentCoroutineContext().ensureActive()
        val temporary = File(directory, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(bytes); currentCoroutineContext().ensureActive()
            check(temporary.renameTo(file))
        } finally { temporary.delete() }
        directory.listFiles()?.filter { it.extension == "mp3" && it != file }?.sortedByDescending { it.lastModified() }?.drop(11)?.forEach { it.delete() }
        file
    }
}
