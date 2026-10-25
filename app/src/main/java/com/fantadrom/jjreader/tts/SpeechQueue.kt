package com.fantadrom.jjreader.tts

import kotlinx.coroutines.*
import java.io.File

/** P0's five-phrase queue. Dependencies make cancellation/prefetch races testable without audio hardware. */
class SpeechQueue(private val scope: CoroutineScope,
                  private val diagnostics: SpeechDiagnostics,
                  private val fetch: suspend (Phrase, Voice, SpeechDiagnostics.Attempt) -> File,
                  private val play: suspend (File, SpeechDiagnostics.Attempt, suspend () -> Unit) -> Unit,
                  private val highlight: suspend (Phrase?) -> Unit,
                  private val status: (String) -> Unit,
                  private val canPlay: () -> Boolean,
                  private val acquire: () -> Boolean,
                  private val stop: () -> Unit,
                  private val io: CoroutineDispatcher = Dispatchers.IO) {
    enum class State { IDLE, LOADING, PLAYING, PAUSED, COMPLETED, ERROR, CLOSED }
    var state = State.IDLE; private set
    val active get() = state == State.LOADING || state == State.PLAYING
    var cursor = 0; private set
    private var phrases = emptyList<Phrase>()
    private var voice = Voice.DMITRY
    private var generation = 0L
    private var job: Job? = null
    private var current: SpeechDiagnostics.Attempt? = null
    fun start(items: List<Phrase>, selected: Voice) {
        if (state == State.CLOSED) { status("Озвучка закрыта"); return }
        cancel(false)
        phrases = items.take(5); cursor = 0; voice = selected; state = State.IDLE
        diagnostics.beginRun(generation)
        resume()
    }
    fun resume() {
        when {
            state == State.CLOSED -> { status("Озвучка закрыта"); return }
            phrases.isEmpty() -> { status("Нет очереди. Нажмите «5 фраз» в открытой книге."); return }
            cursor >= phrases.size -> { status("Очередь завершена. Нажмите «5 фраз» для нового запуска."); return }
            active -> { status("Озвучка уже запущена"); return }
            !canPlay() -> { status("Продолжение доступно после возврата в приложение"); return }
            !acquire() -> { status("Нет аудиофокуса. Повторите запуск вручную."); return }
        }
        state = State.LOADING
        val run = generation
        diagnostics.continueRun(run)
        val items = phrases.toList()
        val runVoice = voice
        job = scope.launch {
            try {
                highlight(null)
                supervisorScope {
                    fun prefetch(index: Int): Deferred<Result<Pair<File, SpeechDiagnostics.Attempt>>> = async(io) {
                        val attempt = diagnostics.attempt(runVoice)
                        try { Result.success(fetch(items[index], runVoice, attempt) to attempt) }
                        catch (e: CancellationException) { attempt.record("cancelled", code = "CANCELLED"); throw e }
                        catch (e: Exception) {
                            attempt.record(if (e is EdgeFailure) e.stage else "receive", error = e, code = if (e is EdgeFailure) e.code else "FETCH")
                            Result.failure(e)
                        }
                    }
                    var ready = prefetch(cursor)
                    while (cursor < items.size) {
                        state = State.LOADING
                        status("Озвучка: ${voice.label}, фраза ${cursor + 1}/${phrases.size}")
                        val (file, attempt) = ready.await().getOrThrow()
                        current = attempt
                        val next = if (cursor + 1 < items.size) prefetch(cursor + 1) else null
                        ensureActive(); check(run == generation)
                        play(file, attempt) {
                            ensureActive(); if (run != generation) throw CancellationException()
                            highlight(items[cursor]); state = State.PLAYING; attempt.record("playback")
                        }
                        ensureActive()
                        attempt.record("completed")
                        current = null
                        cursor++
                        if (next != null) ready = next
                    }
                }
                if (run == generation) { state = State.COMPLETED; highlight(null); status("Очередь завершена. Новый запуск — «5 фраз».") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (run == generation) {
                    state = State.ERROR
                    current?.record(if (e is EdgeFailure) e.stage else "playback", error = e, code = if (e is EdgeFailure) e.code else "PLAYBACK")
                    val code = if (e is EdgeFailure) "${e.stage}/${e.code}" else "PLAYBACK/${e.javaClass.simpleName}"
                    status("Озвучка: $code. «Продолжить» повторит текущую фразу; доступна диагностика.")
                    current = null
                }
            } finally { if (run == generation) stop() }
        }
    }
    private fun cancel(show: Boolean) {
        generation++; current?.record("cancelled", code = "CANCELLED"); current = null
        job?.cancel(); job = null; stop()
        if (show) status("Пауза. Продолжение — вручную, с начала фразы.")
    }
    fun pause() {
        if (state == State.CLOSED || state == State.IDLE || state == State.COMPLETED) return
        cancel(true); if (cursor < phrases.size) state = State.PAUSED
    }
    fun reset() { cancel(false); phrases = emptyList(); cursor = 0; state = State.IDLE; val run = generation; scope.launch { if (run == generation) highlight(null) } }
    fun close() { reset(); state = State.CLOSED }
}
