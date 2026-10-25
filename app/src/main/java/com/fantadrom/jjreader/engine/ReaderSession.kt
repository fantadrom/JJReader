package com.fantadrom.jjreader.engine

import android.net.Uri
import com.fantadrom.jjreader.books.Book
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import java.util.UUID

/** One book/navigator lifetime. Native start events never overwrite a durable text anchor. */
class ReaderSession(val book: Book, private val notes: NoteStore,
                    val nav: EpubNavigatorFragment, private val bridge: LocatorBridge) {
    var ready = false; private set
    var documentId: String? = null; private set
    private var revision = 0L
    private val verified = mutableSetOf<String>()
    private val hashes = book.chapters.map { TextModel.hash(it.text.toByteArray()) }
    private val initialPosition = notes.data.optJSONObject("positionV2")?.let { JSONObject(it.toString()) }
    private val initialLocator = notes.data.optJSONObject("locator")?.let { JSONObject(it.toString()) }
    fun chapterIndex() = book.chapters.indexOfFirst { it.href.substringBefore('#') == nav.currentLocator.value.href.toString().substringBefore('#') }
    private fun matches(url: String, index: Int): Boolean =
        Uri.parse(url).path?.trimStart('/') == Uri.parse(book.chapters[index].href).path?.trimStart('/')
    suspend fun context(expected: Int = chapterIndex()): JSONObject = withTimeout(12000) {
        require(expected in book.chapters.indices)
        while (true) {
            val state = bridge.call(nav, "JJ.context()")
            currentCoroutineContext().ensureActive()
            if (state != null && chapterIndex() == expected && matches(state.getString("url"), expected)) {
                check(state.getBoolean("nativeReady")) { "READIUM_SCRIPT_MISSING" }
                val id = state.getString("documentId")
                if (id !in verified) {
                    check(bridge.text(nav) == book.chapters[expected].text) { "DOM_MODEL_MISMATCH" }
                    val after = bridge.call(nav, "JJ.context()")
                    currentCoroutineContext().ensureActive()
                    if (after?.optString("documentId") != id) continue
                    verified += id
                }
                documentId = id
                return@withTimeout state
            }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("DOM_NOT_READY")
    }
    fun freeze() { revision++; ready = false }
    suspend fun restoreInitial() {
        freeze()
        val saved = notes.data.optJSONObject("positionV2") ?: initialPosition
        if (saved != null) {
            val index = saved.getInt("chapter")
            validate(saved, index)
            restore(saved, index)
        } else {
            val locator = initialLocator?.let { Locator.fromJSON(it) }
            val index = locator?.let { l -> book.chapters.indexOfFirst { it.href.substringBefore('#') == l.href.toString().substringBefore('#') } }?.takeIf { it >= 0 } ?: 0
            context(index)
            val dom = initialLocator?.optJSONObject("locations")?.optJSONObject("domRange")
            val anchor = dom?.let { bridge.call(nav, "JJ.resolveDom($it)") }
            if (anchor != null) restore(anchor, index)
            else {
                // Legacy records may contain only a resource/progression. Preserve that fallback;
                // all new writes below use a verified code-point range and text hash.
                if (locator != null) nav.go(locator, animated = false)
                context(index)
                bridge.call(nav, "JJ.viewport()") // Request a laid-out DOM, rather than trusting collect's first event.
                ready = true
            }
        }
        capture()
    }
    fun validate(anchor: JSONObject, index: Int) {
        require(index in book.chapters.indices) { "ANCHOR_RESOURCE" }
        if (anchor.has("bookId")) check(anchor.getString("bookId") == book.id) { "ANCHOR_BOOK" }
        if (anchor.has("textHash")) check(anchor.getString("textHash") == hashes[index]) { "ANCHOR_HASH" }
        val start = anchor.getInt("start"); val end = anchor.getInt("end")
        require(start >= 0 && end > start && end <= TextModel.length(book.chapters[index].text)) { "ANCHOR_RANGE" }
        check(TextModel.slice(book.chapters[index].text, start, end) == anchor.getString("quote")) { "ANCHOR_QUOTE" }
    }
    suspend fun restore(anchor: JSONObject, index: Int = anchor.getInt("chapter")) {
        freeze()
        validate(anchor, index)
        val state = context(index)
        val id = UUID.randomUUID().toString()
        val ok = bridge.call(nav, "({ok:JJ.requestRestore(${JSONObject.quote(id)},${anchor.getInt("start")},${anchor.getInt("end")})})")
        check(ok?.getBoolean("ok") == true) { "ANCHOR_SCROLL" }
        withTimeout(12000) {
            while (true) {
                val result = bridge.call(nav, "JJ.restoreStatus()")
                currentCoroutineContext().ensureActive()
                if (result?.optString("id") == id) {
                    check(result.getString("documentId") == state.getString("documentId") && chapterIndex() == index) { "STALE_DOM" }
                    check(result.getBoolean("shown")) { "ANCHOR_NOT_VISIBLE" }
                    break
                }
                delay(32)
            }
        }
        ready = true
    }
    suspend fun capture(): JSONObject? {
        if (!ready) return null
        val capturedRevision = revision
        val index = chapterIndex(); val state = context(index)
        val snap = bridge.call(nav, "JJ.viewport()") ?: return null
        currentCoroutineContext().ensureActive()
        if (!ready || revision != capturedRevision || chapterIndex() != index || snap.getString("documentId") != state.getString("documentId")) return null
        val anchor = snap.optJSONObject("anchor") ?: return snap
        validate(anchor, index)
        anchor.put("bookId", book.id).put("chapter", index).put("href", book.chapters[index].href)
            .put("textHash", hashes[index]).put("schema", 1)
        val locator = bridge.locator(nav.currentLocator.value, anchor)
        anchor.put("locator", locator.toJSON())
        notes.data.put("positionV2", anchor).put("locator", locator.toJSON())
        notes.save()
        return snap
    }
    suspend fun selection(session: String): JSONObject {
        // This is the first await in the menu action: one DOM evaluation captures everything.
        val snap = bridge.call(nav, "JJ.takeSelection(${JSONObject.quote(session)})") ?: error("SELECTION_EMPTY")
        currentCoroutineContext().ensureActive()
        val index = chapterIndex()
        check(ready && snap.getString("documentId") == documentId && matches(snap.getString("url"), index)) { "SELECTION_STALE" }
        check(snap.getString("session") == session) { "SELECTION_SESSION" }
        val anchor = snap.getJSONObject("anchor")
        validate(anchor, index)
        anchor.put("locator", bridge.locator(nav.currentLocator.value, anchor).toJSON())
            .put("chapter", index).put("bookId", book.id).put("textHash", hashes[index])
            .put("readiumSelectionPresent", snap.getBoolean("readiumSelection"))
        return anchor
    }
    suspend fun awaitFont(scale: Double) = withTimeout(12000) {
        while (true) {
            val state = context()
            if (kotlin.math.abs(state.optDouble("fontScale", Double.NaN) - scale) < 0.005) break
            delay(32)
        }
    }
}
