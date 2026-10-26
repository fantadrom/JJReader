package com.fantadrom.jjreader.engine

import com.fantadrom.jjreader.books.BookStore
import com.fantadrom.jjreader.text.TextModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Bounded local trace, shared across Activity/process recreation. No free-form strings or payloads. */
class ReadingTrace(private val file: File) {
    enum class Event { CREATE, PAUSE, DESTROY, STORE_LOAD, STORE_WRITE, INITIAL, READY, FREEZE, GO, DOM, RESTORE_REQUEST, ACK_WAIT, ACK, RECOVERY, FALLBACK, COMMIT, CAPTURE, REJECT, NATIVE, DIALOG }
    enum class Source { LIFECYCLE, INITIAL, NOTE, CAPTURE, RESTORE, FONT, IME, NATIVE, NOTES, ACTIVITY, SESSION, DIAGNOSTIC }
    enum class Reason { STALE, CANCELLED, NOT_READY, NO_ANCHOR, NULL_RESULT, RESOURCE_MISMATCH, DOM_MISMATCH, WRITE_READ_MISMATCH, ERROR, OPEN, CLOSE }
    fun begin() = synchronized(file.absolutePath.intern()) { runCatching { BookStore.writeAtomic(file, empty()) }; Unit }
    private fun empty() = JSONObject().put("schema",3).put("clientConfiguration","jj-p0.7-r1").put("coordinateModel","source-local-v1").put("sessionId",UUID.randomUUID().toString()).put("events",JSONArray())
    private fun read() = runCatching { BookStore.readAtomic(file) }.getOrElse { empty() }
    fun json(): JSONObject = synchronized(file.absolutePath.intern()) {
        read().also { root -> val rows=root.getJSONArray("events"); repeat(rows.length()){rows.getJSONObject(it).remove("signature")} }
    }
    fun metadata(value:JSONObject) = synchronized(file.absolutePath.intern()) {
        runCatching {
            val root=read();val safe=JSONObject()
            for(key in listOf("versionName","versionCode","mode","converter","generation","sourceModel","readingSchema","runtime","webView","prepareMs","cacheHit","openMs"))if(value.has(key))safe.put(key,value.get(key))
            root.put("build",safe);BookStore.writeAtomic(file,root)
        };Unit
    }
    private fun safeGeometry(value: JSONObject): JSONObject {
        val output=JSONObject()
        for(key in listOf("viewportWidth","viewportHeight","scrollY","firstOffset")) {
            val number=value.optDouble(key,Double.NaN);if(number.isFinite())output.put(key,kotlin.math.round(number*100)/100)
        }
        for(key in listOf("layoutReady","layoutStable","shown","fontReady","documentReady"))
            if(value.opt(key) is Boolean)output.put(key,value.getBoolean(key))
        for(key in listOf("firstRect","rangeRect"))value.optJSONObject(key)?.let{rect->
            val safe=JSONObject();for(field in listOf("left","top","right","bottom","width","height")){
                val number=rect.optDouble(field,Double.NaN);if(number.isFinite())safe.put(field,kotlin.math.round(number*100)/100)
            };output.put(key,safe)
        }
        value.optString("phase").takeIf{it in setOf("pending","layout_wait","confirmed","failed")}?.let{output.put("phase",it)}
        return output
    }
    fun record(event: Event, source: Source, activity: String, session: String? = null, book: String? = null,
               revision: Long? = null, target: Int? = null, observed: Int? = null, dom: Int? = null,
               document: String? = null, start: Int? = null, end: Int? = null, ready: Boolean? = null,
               accepted: Boolean? = null, diskChapter: Int? = null, diskStart: Int? = null,
               locatorChapter: Int? = null, store: String? = null, reason: Reason? = null, operation: Long? = revision, diskBook: String? = null, failureCode: RestoreCode? = null, requestId: String? = null,
               geometry: JSONObject? = null, sessionPhase: String? = null, renderedResource:Int?=null, sourceSection:Int?=null,durationMs:Long?=null) {
        synchronized(file.absolutePath.intern()) {
            runCatching {
                val root=read().put("schema",3).put("clientConfiguration","jj-p0.7-r1").put("coordinateModel","source-local-v1");val events=root.getJSONArray("events")
                val row=JSONObject().put("event",event.name).put("source",source.name).put("activity",activity)
                // Book/document IDs are hashed here, never accept a URL/title/quote as an exported field.
                session?.let{row.put("readerSession",it)};book?.let{row.put("book",TextModel.hash(it.toByteArray()).take(16))}
                diskBook?.let{row.put("diskBook",TextModel.hash(it.toByteArray()).take(16))}
                document?.let{row.put("document",TextModel.hash(it.toByteArray()).take(16))};store?.let{row.put("store",it)}
                revision?.let{row.put("revision",it)};operation?.let{row.put("operation",it)};target?.let{row.put("target",it)};observed?.let{row.put("observed",it)}
                renderedResource?.let{row.put("renderedResource",it)};sourceSection?.let{row.put("sourceSection",it)}
                dom?.let{row.put("dom",it)};start?.let{row.put("start",it)};end?.let{row.put("end",it)}
                ready?.let{row.put("ready",it)};accepted?.let{row.put("accepted",it)};diskChapter?.let{row.put("diskChapter",it)}
                diskStart?.let{row.put("diskStart",it)};locatorChapter?.let{row.put("locatorChapter",it)};reason?.let{row.put("reason",it.name)}
                failureCode?.let{row.put("failureCode",it.name)};requestId?.let{row.put("requestId",it)}
                sessionPhase?.takeIf{it in setOf("NEW","FROZEN","RESTORING","RECOVERING","TRACKING")}?.let{row.put("sessionPhase",it)}
                geometry?.let{row.put("geometry",safeGeometry(it))}
                // Suppress identical periodic observations; preserve operation/lifecycle edges.
                val signature=row.toString()
                if(event in setOf(Event.DOM,Event.ACK_WAIT,Event.CAPTURE,Event.COMMIT,Event.STORE_WRITE,Event.REJECT,Event.NATIVE)) {
                    val previous=(events.length()-1 downTo 0).asSequence().map{events.getJSONObject(it)}
                        .firstOrNull{it.optString("event")==event.name}
                    if(previous?.optString("signature")==signature)return@runCatching
                }
                if(event in setOf(Event.ACK,Event.RECOVERY,Event.REJECT))durationMs?.let{row.put("durationMs",it)}
                row.put("signature",signature).put("timeMs",System.currentTimeMillis())
                events.put(row);while(events.length()>256)events.remove(0)
                BookStore.writeAtomic(file,root)
            }
        }
    }
}
