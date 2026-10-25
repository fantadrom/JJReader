package com.fantadrom.jjreader.engine

import android.net.Uri
import com.fantadrom.jjreader.books.Book
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.CancellationException
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
                    private val save: () -> Unit, private val port: ReaderPort) {
    constructor(book: Book, notes: NoteStore, nav: EpubNavigatorFragment, bridge: LocatorBridge,
                goResource: suspend (Int) -> Boolean) : this(book, notes.data, notes::save, object : ReaderPort {
        override fun chapterIndex() = book.chapters.indexOfFirst { it.href.substringBefore('#') == nav.currentLocator.value.href.toString().substringBefore('#') }
        override suspend fun call(expression: String) = bridge.call(nav, expression)
        override suspend fun text() = bridge.text(nav)
        override suspend fun goResource(index: Int) = goResource.invoke(index)
        override suspend fun go(locator: Locator) { nav.go(locator, animated = false) }
        override fun locator(anchor: JSONObject) = bridge.locator(nav.currentLocator.value, anchor)
    })
    var ready = false; private set
    var documentId: String? = null; private set
    private var revision = 0L
    private val verified = mutableSetOf<String>()
    private val hashes = book.chapters.map { TextModel.hash(it.text.toByteArray()) }
    fun chapterIndex() = port.chapterIndex()
    internal fun requireCurrent(operation: Long) = guard(operation)
    private fun guard(operation: Long) {
        if (operation != revision) throw StaleReaderOperation()
    }
    private suspend fun afterAwait(operation: Long) { currentCoroutineContext().ensureActive(); guard(operation) }
    private fun matches(url: String, index: Int): Boolean =
        Uri.parse(url).path?.trimStart('/') == Uri.parse(book.chapters[index].href).path?.trimStart('/')
    suspend fun context(expected: Int = chapterIndex(), operation: Long = revision): JSONObject = withTimeout(12000) {
        require(expected in book.chapters.indices)
        while (true) {
            afterAwait(operation)
            val state = port.call("JJ.context()")
            afterAwait(operation)
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
    fun freeze(): Long { revision++; ready = false; return revision }
    suspend fun restoreInitial() {
        val operation = freeze()
        val saved = data.optJSONObject("positionV2")?.let { JSONObject(it.toString()) }
        val locatorJson = data.optJSONObject("locator")?.let { JSONObject(it.toString()) }
        if (saved != null) {
            val index = saved.getInt("chapter")
            validate(saved, index)
            if (chapterIndex() >= 0 && chapterIndex() != index) {
                val accepted = port.goResource(index); afterAwait(operation)
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
                ready = true
            }
        }
        guard(operation)
        capture()
    }
    /** One operation owns resource navigation, acknowledgement and synchronous durable commit. */
    suspend fun navigateTo(anchor: JSONObject) {
        val operation = freeze()
        val index = anchor.getInt("chapter")
        validate(anchor, index)
        val accepted = port.goResource(index); afterAwait(operation)
        check(accepted) { "RESOURCE_NAVIGATION" }
        restore(anchor, index, operation)
        guard(operation)
        capture() // Optional viewport refinement; target is already durable even if this returns null.
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
        validate(anchor, index)
        val state = context(index, operation)
        val id = UUID.randomUUID().toString()
        val ok = port.call("({ok:JJ.requestRestore(${JSONObject.quote(id)},${anchor.getInt("start")},${anchor.getInt("end")})})")
        afterAwait(operation)
        check(ok?.getBoolean("ok") == true) { "ANCHOR_SCROLL" }
        withTimeout(12000) {
            while (true) {
                val result = port.call("JJ.restoreStatus()")
                afterAwait(operation)
                if (result?.optString("id") == id) {
                    check(result.getString("documentId") == state.getString("documentId") && chapterIndex() == index) { "STALE_DOM" }
                    check(result.getBoolean("shown")) { "ANCHOR_NOT_VISIBLE" }
                    break
                }
                delay(32)
            }
        }
        guard(operation)
        persistAnchor(anchor, index, operation)
        ready = true
    }
    private fun persistAnchor(value: JSONObject, index: Int, operation: Long) {
        guard(operation); validate(value, index)
        val anchor = JSONObject(value.toString())
        anchor.put("bookId", book.id).put("chapter", index).put("href", book.chapters[index].href)
            .put("textHash", hashes[index]).put("schema", 1)
        val locator = port.locator(anchor)
        anchor.put("locator", locator.toJSON())
        data.put("positionV2", anchor).put("locator", locator.toJSON())
        save()
    }
    suspend fun capture(): JSONObject? {
        if (!ready) return null
        val operation = revision
        val index = chapterIndex(); val state = context(index, operation)
        val snap = port.call("JJ.viewport()") ?: return null
        afterAwait(operation)
        if (!ready || revision != operation || chapterIndex() != index || snap.getString("documentId") != state.getString("documentId")) return null
        val anchor = snap.optJSONObject("anchor") ?: return snap
        validate(anchor, index)
        persistAnchor(anchor, index, operation)
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
