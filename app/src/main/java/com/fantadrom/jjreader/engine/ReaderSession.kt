package com.fantadrom.jjreader.engine

import android.net.Uri
import com.fantadrom.jjreader.books.Book
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import java.util.UUID

/** Narrow native boundary; deterministic tests exercise the same persistence/operation code. */
class StaleReaderOperation : CancellationException("STALE_READER_OPERATION")

internal interface ReaderPort {
    fun chapterIndex(): Int
    suspend fun call(expression: String): JSONObject?
    suspend fun text(): String?
    suspend fun goResource(index: Int): Boolean
    suspend fun go(locator: Locator)
    fun locator(anchor: JSONObject): Locator
}

/** One book/navigator lifetime. Native start events never overwrite a durable text anchor. */
class ReaderSession internal constructor(val book: Book, private val data: JSONObject,
                    private val save: () -> Unit, private val port: ReaderPort,
                    private val trace: ReadingTrace? = null, private val activityId: String = "test") {
    constructor(book: Book, notes: NoteStore, nav: EpubNavigatorFragment, bridge: LocatorBridge,
                goResource: suspend (Int) -> Boolean, trace: ReadingTrace? = null, activityId: String = "test") : this(book, notes.data, { notes.save(NoteStore.Source.SESSION) }, object : ReaderPort {
        override fun chapterIndex() = book.chapters.indexOfFirst { it.href.substringBefore('#') == nav.currentLocator.value.href.toString().substringBefore('#') }
        override suspend fun call(expression: String) = bridge.call(nav, expression)
        override suspend fun text() = bridge.text(nav)
        override suspend fun goResource(index: Int) = goResource.invoke(index)
        override suspend fun go(locator: Locator) { nav.go(locator, animated = false) }
        override fun locator(anchor: JSONObject) = bridge.locator(nav.currentLocator.value, anchor)
    }, trace, activityId)
    val id = UUID.randomUUID().toString()
    private var source = ReadingTrace.Source.INITIAL
    enum class Phase { NEW, FROZEN, RESTORING, RECOVERING, TRACKING }
    var phase = Phase.NEW; private set
    val ready get() = phase == Phase.TRACKING
    private var initialAttempted = false
    val shouldRestoreInitial get() = !initialAttempted && phase == Phase.NEW
    var lastRestoreCode: RestoreCode? = null; private set
    private var activeRequest: String? = null
    private var activeTarget: Int? = null
    var documentId: String? = null; private set
    private var revision = 0L
    private var lastObservedIndex = chapterIndex()
    private val verified = mutableSetOf<String>()
    private val hashes = book.chapters.map { TextModel.hash(it.text.toByteArray()) }
    fun chapterIndex() = port.chapterIndex()
    fun traceState(event: ReadingTrace.Event, from: ReadingTrace.Source = source) = emit(event, from)
    private fun indexOf(url: String) = book.chapters.indices.firstOrNull { matches(url,it) } ?: -1
    private fun emit(event: ReadingTrace.Event, from: ReadingTrace.Source = source, target: Int? = null,
                     state: JSONObject? = null, anchor: JSONObject? = null, accepted: Boolean? = null,
                     reason: ReadingTrace.Reason? = null, operation: Long = revision, failure: RestoreCode? = null) {
        trace?.record(event,from,activityId,id,book.id,revision,target,chapterIndex(),
            state?.optString("url")?.let(::indexOf),state?.optString("documentId") ?: documentId,
            anchor?.optInt("start"),anchor?.optInt("end"),ready,accepted,reason=reason,operation=operation,failureCode=failure,requestId=activeRequest,geometry=state,sessionPhase=phase.name)
    }
    internal fun requireCurrent(operation: Long) = guard(operation)
    private fun guard(operation: Long) {
        if (operation != revision) { emit(ReadingTrace.Event.REJECT, reason=ReadingTrace.Reason.STALE,operation=operation,failure=RestoreCode.STALE_REVISION); throw StaleReaderOperation() }
    }
    private suspend fun afterAwait(operation: Long) { currentCoroutineContext().ensureActive(); guard(operation) }
    private fun positiveViewport(state: JSONObject) = listOf("viewportWidth","viewportHeight").all {
        state.optDouble(it,Double.NaN).let{value->value.isFinite() && value>0}
    }
    private fun matches(url: String, index: Int): Boolean =
        Uri.parse(url).path?.trimStart('/') == Uri.parse(book.chapters[index].href).path?.trimStart('/')
    suspend fun context(expected: Int = chapterIndex(), operation: Long = revision): JSONObject = withTimeout(12000) {
        require(expected in book.chapters.indices)
        while (true) {
            afterAwait(operation)
            val state = port.call("JJ.context()")
            afterAwait(operation)
            emit(ReadingTrace.Event.DOM,target=expected,state=state,accepted=state!=null && chapterIndex()==expected && matches(state.getString("url"),expected),operation=operation)
            if (state != null && chapterIndex() == expected && matches(state.getString("url"), expected)) {
                check(state.getBoolean("nativeReady")) { "READIUM_SCRIPT_MISSING" }
                val id = state.getString("documentId")
                if (id !in verified) {
                    val text = port.text(); afterAwait(operation)
                    check(text == book.chapters[expected].text) { "DOM_MODEL_MISMATCH" }
                    val after = port.call("JJ.context()")
                    afterAwait(operation)
                    if (after?.optString("documentId") != id || chapterIndex() != expected || !matches(after.getString("url"), expected)) continue
                    verified += id
                }
                documentId = id
                return@withTimeout state
            }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("DOM_NOT_READY")
    }
    fun freeze(from: ReadingTrace.Source = source): Long {
        source = from; revision++; phase = Phase.FROZEN; activeRequest = null; activeTarget = null
        emit(ReadingTrace.Event.FREEZE); return revision
    }
    /** Supersede a failed/pending initial target when the reader moves, never replay it on resume. */
    fun userNavigation() {
        revision++; initialAttempted = true; phase = Phase.RECOVERING
        emit(ReadingTrace.Event.RECOVERY,ReadingTrace.Source.NATIVE,failure=RestoreCode.RESTORE_INTERRUPTED)
        activeRequest = null; activeTarget = null
    }
    fun observeResource() {
        val observed=chapterIndex();val previous=lastObservedIndex;lastObservedIndex=observed
        if(phase==Phase.RESTORING && observed>=0 && observed!=previous && observed!=activeTarget)userNavigation()
    }
    fun preparePause() {
        if (phase == Phase.RESTORING || (phase == Phase.FROZEN && source == ReadingTrace.Source.FONT)) {
            revision++; phase = Phase.RECOVERING; lastRestoreCode = RestoreCode.CANCELLED
            emit(ReadingTrace.Event.REJECT,ReadingTrace.Source.LIFECYCLE,failure=RestoreCode.CANCELLED)
            activeRequest = null; activeTarget = null
        }
    }
    fun traceFallback() = emit(ReadingTrace.Event.FALLBACK,ReadingTrace.Source.LIFECYCLE,
        target=data.optJSONObject("positionV2")?.optInt("chapter"),anchor=data.optJSONObject("positionV2"),failure=lastRestoreCode)
    private fun code(error: Exception, stage: String): RestoreCode = when(error) {
        is RestoreFailure -> error.code
        is TimeoutCancellationException -> if(stage == "ack")RestoreCode.ACK_TIMEOUT else RestoreCode.DOM_TIMEOUT
        is CancellationException -> RestoreCode.CANCELLED
        is org.json.JSONException -> RestoreCode.JS_RESULT
        else -> RestoreCode.entries.firstOrNull{it.name == error.message} ?: if(stage == "save")RestoreCode.SAVE_ERROR else RestoreCode.INTERNAL_ERROR
    }
    private suspend fun failed(error: Exception, operation: Long, stage: String, state: JSONObject? = null): Boolean {
        if (revision != operation) throw StaleReaderOperation()
        lastRestoreCode = code(error,stage); phase = Phase.RECOVERING
        emit(ReadingTrace.Event.REJECT,state=state,failure=lastRestoreCode)
        activeRequest = null; activeTarget = null
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        currentCoroutineContext().ensureActive()
        return recoverVisible(operation)
    }
    private suspend fun recoverVisible(operation: Long): Boolean {
        return try {
            withTimeout(1500) {
                while(true) {
                    afterAwait(operation)
                    if(capture() != null && ready) return@withTimeout true
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") false
            }
        } catch(e: CancellationException) {
            if(e !is TimeoutCancellationException)throw e
            guard(operation);emit(ReadingTrace.Event.FALLBACK,failure=lastRestoreCode);false
        } catch(e: Exception) {
            guard(operation);emit(ReadingTrace.Event.FALLBACK,failure=code(e,"capture"));false
        }
    }
    suspend fun restoreInitial() {
        source = ReadingTrace.Source.INITIAL
        initialAttempted = true
        val operation = freeze()
        try {
            val saved = data.optJSONObject("positionV2")?.let { JSONObject(it.toString()) }
            emit(ReadingTrace.Event.INITIAL,target=saved?.optInt("chapter"),anchor=saved)
            val locatorJson = data.optJSONObject("locator")?.let { JSONObject(it.toString()) }
            if (saved != null) {
                val index = saved.getInt("chapter")
                validate(saved, index)
                if (chapterIndex() >= 0 && chapterIndex() != index) {
                    emit(ReadingTrace.Event.GO,target=index)
                    val accepted = port.goResource(index); afterAwait(operation)
                    emit(ReadingTrace.Event.GO,target=index,accepted=accepted)
                    check(accepted) { "RESOURCE_NAVIGATION" }
                }
                restore(saved, index, operation)
            } else {
                val locator = locatorJson?.let { Locator.fromJSON(it) }
                val index = locator?.let { l -> book.chapters.indexOfFirst { it.href.substringBefore('#') == l.href.toString().substringBefore('#') } }?.takeIf { it >= 0 } ?: 0
                context(index, operation)
                val dom = locatorJson?.optJSONObject("locations")?.optJSONObject("domRange")
                val anchor = dom?.let { port.call("JJ.resolveDom($it)").also { afterAwait(operation) } }
                if (anchor != null) restore(anchor, index, operation)
                else {
                    if (locator != null) { port.go(locator); afterAwait(operation) }
                    context(index, operation)
                    port.call("JJ.viewport()"); afterAwait(operation)
                    phase = Phase.RECOVERING
                    recoverVisible(operation)
                }
            }
            guard(operation)
            capture()
        } catch(e: Exception) {
            if(e is StaleReaderOperation)throw e
            if(e is CancellationException && e !is TimeoutCancellationException) {
                if(revision==operation) {
                    phase=Phase.RECOVERING;lastRestoreCode=RestoreCode.CANCELLED
                    emit(ReadingTrace.Event.REJECT,failure=RestoreCode.CANCELLED)
                }
                throw e
            }
            if(phase != Phase.RECOVERING && !failed(e,operation,"initial"))throw RestoreFailure(checkNotNull(lastRestoreCode))
            if(!ready)throw RestoreFailure(lastRestoreCode ?: code(e,"initial"))
        }
    }
    /** One operation owns resource navigation, acknowledgement and synchronous durable commit. */
    suspend fun navigateTo(anchor: JSONObject) {
        source = ReadingTrace.Source.NOTE
        initialAttempted = true
        val operation = freeze()
        try {
            val index = anchor.getInt("chapter")
            validate(anchor, index)
            emit(ReadingTrace.Event.GO,target=index)
            val accepted = port.goResource(index); afterAwait(operation)
            emit(ReadingTrace.Event.GO,target=index,accepted=accepted)
            check(accepted) { "RESOURCE_NAVIGATION" }
            restore(anchor, index, operation)
            guard(operation)
            capture() // Optional viewport refinement; target is already durable even if this returns null.
        } catch(e: Exception) {
            if(e is StaleReaderOperation)throw e
            if(e is CancellationException && e !is TimeoutCancellationException) {
                if(revision==operation) {
                    phase=Phase.RECOVERING;lastRestoreCode=RestoreCode.CANCELLED
                    emit(ReadingTrace.Event.REJECT,failure=RestoreCode.CANCELLED)
                }
                throw e
            }
            if(phase != Phase.RECOVERING && !failed(e,operation,"navigate"))throw RestoreFailure(checkNotNull(lastRestoreCode))
            if(!ready)throw RestoreFailure(lastRestoreCode ?: code(e,"navigate"))
        }
    }
    fun validate(anchor: JSONObject, index: Int) {
        require(index in book.chapters.indices) { "ANCHOR_RESOURCE" }
        if (anchor.has("bookId")) check(anchor.getString("bookId") == book.id) { "ANCHOR_BOOK" }
        if (anchor.has("textHash")) check(anchor.getString("textHash") == hashes[index]) { "ANCHOR_HASH" }
        val start = anchor.getInt("start"); val end = anchor.getInt("end")
        require(start >= 0 && end > start && end <= TextModel.length(book.chapters[index].text)) { "ANCHOR_RANGE" }
        check(TextModel.slice(book.chapters[index].text, start, end) == anchor.getString("quote")) { "ANCHOR_QUOTE" }
    }
    suspend fun restore(anchor: JSONObject, index: Int = anchor.getInt("chapter"), operation: Long = freeze()) {
        guard(operation)
        phase = Phase.RESTORING; activeTarget = index
        var stage = "validate"
        var last: JSONObject? = null
        try {
            validate(anchor,index)
            stage = "context"
            val state = context(index,operation)
            activeRequest = UUID.randomUUID().toString()
            val request = checkNotNull(activeRequest)
            stage = "request"
            val ok = port.call("({ok:JJ.requestRestore(${JSONObject.quote(request)},${anchor.getInt("start")},${anchor.getInt("end")})})")
            afterAwait(operation)
            emit(ReadingTrace.Event.RESTORE_REQUEST,target=index,state=state,anchor=anchor,accepted=ok?.optBoolean("ok"))
            if(ok?.optBoolean("ok") != true)throw RestoreFailure(RestoreCode.ANCHOR_SCROLL)
            stage = "ack"
            withTimeout(6000) {
                while(true) {
                    val result = port.call("JJ.restoreStatus()")
                    afterAwait(operation); last = result
                    if(result != null) {
                        if(result.optString("id") != request || result.optString("documentId") != state.getString("documentId") ||
                            chapterIndex() != index || !matches(result.optString("url"),index))throw RestoreFailure(RestoreCode.STALE_DOM)
                        emit(ReadingTrace.Event.ACK_WAIT,target=index,state=result,anchor=anchor)
                        when(result.optString("phase")) {
                            "confirmed" -> {
                                if(!result.optBoolean("shown"))throw RestoreFailure(RestoreCode.ANCHOR_NOT_VISIBLE)
                                if(!result.optBoolean("layoutReady") || !result.optBoolean("layoutStable") ||
                                    !positiveViewport(result))throw RestoreFailure(RestoreCode.LAYOUT_UNSTABLE)
                                break
                            }
                            "failed" -> throw RestoreFailure(RestoreCode.entries.firstOrNull{it.name == result.optString("reason")} ?: RestoreCode.JS_RESULT)
                            "pending", "layout_wait" -> Unit
                            else -> throw RestoreFailure(RestoreCode.JS_RESULT)
                        }
                    }
                    delay(40)
                }
            }
            guard(operation)
            emit(ReadingTrace.Event.ACK,target=index,state=last,anchor=anchor,accepted=true)
            stage = "save"
            persistAnchor(anchor,index,operation)
            lastRestoreCode = null; phase = Phase.TRACKING
            emit(ReadingTrace.Event.READY,target=index)
        } catch(e: Exception) {
            if(e is StaleReaderOperation)throw e
            if(!failed(e,operation,stage,last))throw RestoreFailure(checkNotNull(lastRestoreCode))
        } finally {
            if(revision == operation) {activeRequest=null;activeTarget=null}
        }
    }
    private fun persistAnchor(value: JSONObject, index: Int, operation: Long, from: ReadingTrace.Source = source) {
        guard(operation); validate(value, index)
        val anchor = JSONObject(value.toString())
        anchor.put("bookId", book.id).put("chapter", index).put("href", book.chapters[index].href)
            .put("textHash", hashes[index]).put("schema", 1)
        val locator = port.locator(anchor)
        anchor.put("locator", locator.toJSON())
        data.put("positionV2", anchor).put("locator", locator.toJSON())
        save()
        trace?.record(ReadingTrace.Event.COMMIT,from,activityId,id,book.id,revision,index,chapterIndex(),
            document=documentId,start=anchor.getInt("start"),end=anchor.getInt("end"),ready=ready,
            locatorChapter=indexOf(locator.href.toString()))
    }
    suspend fun capture(): JSONObject? {
        if (phase != Phase.TRACKING && phase != Phase.RECOVERING) { emit(ReadingTrace.Event.REJECT,ReadingTrace.Source.CAPTURE,reason=ReadingTrace.Reason.NOT_READY); return null }
        val operation = revision
        val index = chapterIndex(); val state = context(index, operation)
        val snap = port.call("JJ.viewport()") ?: run { emit(ReadingTrace.Event.REJECT,ReadingTrace.Source.CAPTURE,reason=ReadingTrace.Reason.NULL_RESULT); return null }
        afterAwait(operation)
        if (revision != operation || chapterIndex() != index || snap.optString("documentId") != state.getString("documentId") || !matches(snap.optString("url"),index)) return null
        if(!snap.optBoolean("layoutReady") || !snap.optBoolean("layoutStable") || !positiveViewport(snap) || !snap.optBoolean("shown")) {
            emit(ReadingTrace.Event.REJECT,ReadingTrace.Source.CAPTURE,state=snap,failure=RestoreCode.LAYOUT_UNSTABLE);return null
        }
        val anchor = snap.optJSONObject("anchor") ?: run { emit(ReadingTrace.Event.REJECT,ReadingTrace.Source.CAPTURE,state=snap,reason=ReadingTrace.Reason.NO_ANCHOR); return null }
        emit(ReadingTrace.Event.CAPTURE,ReadingTrace.Source.CAPTURE,index,snap,anchor)
        validate(anchor, index)
        persistAnchor(anchor, index, operation, ReadingTrace.Source.CAPTURE)
        if(!ready) { phase=Phase.TRACKING;emit(ReadingTrace.Event.RECOVERY,ReadingTrace.Source.CAPTURE,index,snap,anchor,accepted=true,failure=lastRestoreCode) }
        return snap
    }
    suspend fun selection(session: String): JSONObject {
        val operation = revision
        // This is the first await in the menu action: one DOM evaluation captures everything.
        val snap = port.call("JJ.takeSelection(${JSONObject.quote(session)})") ?: error("SELECTION_EMPTY")
        afterAwait(operation)
        val index = chapterIndex()
        check(ready && snap.getString("documentId") == documentId && matches(snap.getString("url"), index)) { "SELECTION_STALE" }
        check(snap.getString("session") == session) { "SELECTION_SESSION" }
        val anchor = snap.getJSONObject("anchor")
        validate(anchor, index)
        anchor.put("locator", port.locator(anchor).toJSON())
            .put("chapter", index).put("bookId", book.id).put("textHash", hashes[index])
            .put("readiumSelectionPresent", snap.getBoolean("readiumSelection"))
        return anchor
    }
    suspend fun awaitFont(scale: Double, operation: Long = revision) = withTimeout(12000) {
        while (true) {
            afterAwait(operation)
            val state = context(operation = operation)
            if (kotlin.math.abs(state.optDouble("fontScale", Double.NaN) - scale) < 0.005) break
            delay(32)
        }
    }
}
