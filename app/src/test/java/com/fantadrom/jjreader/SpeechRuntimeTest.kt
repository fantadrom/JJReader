@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.fantadrom.jjreader

import com.fantadrom.jjreader.tts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.UnknownHostException
import java.nio.file.Files

class SpeechRuntimeTest {
    private val items = (0..5).map { Phrase(it * 2, it * 2 + 1, "Фраза $it") }
    @Test fun completeRestartAndEmptyResumeHaveExplicitStates() = runTest {
        val played = mutableListOf<String>(); val messages = mutableListOf<String>(); val highlighted = mutableListOf<Phrase?>()
        val queue = SpeechQueue(this, SpeechDiagnostics(), { _, _, _ -> File("fixture.mp3") },
            { _, _, started -> started(); played += "played" }, { highlighted += it }, messages::add, { true }, { true }, {}, StandardTestDispatcher(testScheduler))
        queue.resume(); assertTrue(messages.last().contains("Нет очереди"))
        queue.start(items, Voice.DMITRY); advanceUntilIdle()
        assertEquals(5, played.size); assertEquals(SpeechQueue.State.COMPLETED, queue.state); assertNull(highlighted.last())
        queue.resume(); assertTrue(messages.last().contains("Очередь завершена"))
        queue.start(items, Voice.DMITRY); advanceUntilIdle(); assertEquals(10, played.size)
    }
    @Test fun failedPrefetchDoesNotInterruptCurrentPlaybackAndExplicitRetryWorks() = runTest {
        val finishFirst = CompletableDeferred<Unit>(); var failures = 0; var finished = false
        val queue = SpeechQueue(this, SpeechDiagnostics(), { phrase, _, _ ->
            if (phrase == items[1] && failures++ == 0) throw EdgeFailure("DNS", "DNS", UnknownHostException("private message"))
            File("fixture.mp3")
        }, { _, _, started -> started(); if (!finished) { finishFirst.await(); finished = true } }, {}, {}, { true }, { true }, {}, StandardTestDispatcher(testScheduler))
        queue.start(items.take(2), Voice.DMITRY); runCurrent()
        assertEquals(SpeechQueue.State.PLAYING, queue.state); assertFalse(finished)
        finishFirst.complete(Unit); advanceUntilIdle()
        assertTrue(finished); assertEquals(1, queue.cursor); assertEquals(SpeechQueue.State.ERROR, queue.state)
        queue.resume(); advanceUntilIdle(); assertEquals(SpeechQueue.State.COMPLETED, queue.state)
    }
    @Test fun pauseVoiceSwitchAndFastRestartCannotPlayAnOldRequest() = runTest {
        val pending = CompletableDeferred<Unit>(); val heard = mutableListOf<Voice>(); var selected = Voice.DMITRY
        val queue = SpeechQueue(this, SpeechDiagnostics(), { _, voice, _ ->
            if (voice == Voice.DMITRY) pending.await(); selected = voice; File("fixture.mp3")
        }, { _, _, started -> started(); heard += selected }, {}, {}, { true }, { true }, {}, StandardTestDispatcher(testScheduler))
        queue.start(items, Voice.DMITRY); runCurrent(); queue.pause(); assertEquals(SpeechQueue.State.PAUSED, queue.state)
        queue.start(items.take(1), Voice.SVETLANA); advanceUntilIdle(); pending.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(Voice.SVETLANA), heard)
        queue.start(items.take(1), Voice.SVETLANA); runCurrent(); queue.pause(); queue.resume(); advanceUntilIdle()
        assertFalse(queue.active)
    }
    @Test fun cacheReuseConcurrentCommitAndFailureRetryAreIndependentOfNetworkAvailability() = runTest {
        val dir = Files.createTempDirectory("p0-cache").toFile(); var requests = 0; var networkFails = false
        val diagnostics = SpeechDiagnostics()
        try {
            val cache = PhraseAudioCache(dir) { _, _, attempt ->
                requests++; attempt.record("receive", source = "network")
                if (networkFails) throw EdgeFailure("HTTP_503", "handshake")
                ByteArray(256) { 7 }
            }
            coroutineScope {
                val a = async { cache.get(items[0], Voice.DMITRY, diagnostics.attempt(Voice.DMITRY)) }
                val b = async { cache.get(items[0], Voice.DMITRY, diagnostics.attempt(Voice.DMITRY)) }
                assertEquals(a.await(), b.await())
            }
            assertEquals(1, requests)
            cache.get(items[0], Voice.SVETLANA, diagnostics.attempt(Voice.SVETLANA)); assertEquals(2, requests)
            networkFails = true
            try { cache.get(items[1], Voice.DMITRY, diagnostics.attempt(Voice.DMITRY)); fail("Expected network error") } catch (_: EdgeFailure) { }
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
            networkFails = false; cache.get(items[1], Voice.DMITRY, diagnostics.attempt(Voice.DMITRY)); assertEquals(6, requests)
        } finally { dir.deleteRecursively() }
    }
    @Test fun diagnosticAllowListRetainsCategoryAndNeverLeaksExceptionContent() {
        val diagnostics = SpeechDiagnostics(); diagnostics.beginRun(7)
        val attempt = diagnostics.attempt(Voice.DMITRY)
        attempt.record("handshake", source = "network", http = 403, close = 1008,
            error = IllegalStateException("https://host/?token=SECRET Cookie SSML маяк", UnknownHostException("SECRET")), code = "HTTP_403")
        val report = diagnostics.text(); val row = JSONObject(report).getJSONArray("attempts").getJSONObject(0)
        assertEquals(403, row.getInt("httpStatus")); assertEquals(1008, row.getInt("webSocketCloseCode"))
        assertEquals("IllegalStateException", row.getString("exception")); assertEquals("UnknownHostException", row.getJSONArray("causes").getString(0))
        listOf("SECRET", "Cookie", "SSML", "https://", "маяк").forEach { assertFalse(report.contains(it)) }
        diagnostics.beginRun(8); attempt.record("completed"); assertEquals(0, JSONObject(diagnostics.text()).getJSONArray("attempts").length())
    }
}
