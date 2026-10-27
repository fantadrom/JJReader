package com.fantadrom.jjreader.engine

import com.fantadrom.jjreader.books.BookStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One owner for explicit navigation, including the queue-owned TTS transition. No pixel persistence. */
class SectionNavigation(private val reader:ReaderSession,private val file:File,private val edges:suspend(Boolean)->Unit={}) {
    enum class Initiator { GESTURE, BUTTON, TOC, NOTE, LINK, TTS, SLIDER, RETURN, TAP }
    private val mutex=Mutex()
    var busy=false;private set
    suspend fun run(initiator:Initiator,target:Int,placement:BoundaryPlacement?=null,metrics:JSONObject?=null,block:suspend()->Unit)=mutex.withLock {
        busy=true
        val start=System.nanoTime();val origin=reader.chapterIndex()
        var outcome="cancelled";var error:String?=null
        try{edges(true);block();currentCoroutineContext().ensureActive();check(reader.ready && reader.chapterIndex()==target && reader.lastRestoreCode==null){"NAVIGATION_NOT_READY"};outcome="success"}
        catch(e:Exception){if(e !is kotlinx.coroutines.CancellationException){outcome="failed";error=(e as? RestoreFailure)?.code?.name ?: "NAVIGATION_FAILED"};throw e}
        finally{
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){kotlinx.coroutines.withTimeoutOrNull(500){runCatching{edges(false)}}}
            busy=false
            val root=runCatching{BookStore.readAtomic(file)}.getOrElse{JSONObject().put("schema",1).put("events",JSONArray())}
            val entry=when {
                initiator==Initiator.TAP && placement==BoundaryPlacement.END_PAGE->"TAP_BACK_END_PAGE"
                initiator==Initiator.TAP && placement==BoundaryPlacement.RESOURCE_START->"TAP_FORWARD_START"
                initiator==Initiator.GESTURE && placement==BoundaryPlacement.END_TOP->"GESTURE_BACK_END"
                placement!=null->placement.name
                else->"ANCHOR"
            }
            val safe=JSONObject()
            metrics?.let{m->for(key in listOf("contentTop","contentBottom","freeBelow","lineHeight","viewportHeight")){val v=m.optDouble(key,Double.NaN);if(v.isFinite())safe.put(key,v)};for(key in listOf("tapAtStart","tapAtEnd","contentStartVisible","contentEndVisible"))if(m.opt(key) is Boolean)safe.put(key,m.getBoolean(key))}
            val rows=root.getJSONArray("events");rows.put(JSONObject().put("session",reader.id).put("book",com.fantadrom.jjreader.text.TextModel.hash(reader.book.id.toByteArray()).take(16)).put("initiator",initiator.name).put("from",origin).put("target",target).put("entry",entry).put("metrics",safe).put("revision",reader.currentRevision).put("outcome",outcome).put("ack",outcome=="success").put("durationMs",(System.nanoTime()-start)/1_000_000).put("errorCode",error))
            while(rows.length()>80)rows.remove(0);BookStore.writeAtomic(file,root)
        }
    }
    suspend fun boundary(target:Int,end:Boolean,initiator:Initiator)=boundary(target,if(end)BoundaryPlacement.END_TOP else BoundaryPlacement.RESOURCE_START,initiator)
    suspend fun boundary(target:Int,placement:BoundaryPlacement,initiator:Initiator,metrics:JSONObject?=null)=run(initiator,target,placement,metrics){reader.navigateBoundary(target,placement,if(initiator==Initiator.TTS)ReadingTrace.Source.RESTORE else ReadingTrace.Source.NATIVE)}
}
