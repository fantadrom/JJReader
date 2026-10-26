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
    enum class Initiator { GESTURE, BUTTON, TOC, NOTE, LINK, TTS }
    private val mutex=Mutex()
    var busy=false;private set
    suspend fun run(initiator:Initiator,target:Int,end:Boolean=false,block:suspend()->Unit)=mutex.withLock {
        busy=true
        val start=System.nanoTime();val origin=reader.chapterIndex()
        var outcome="cancelled";var error:String?=null
        try{edges(true);block();currentCoroutineContext().ensureActive();check(reader.ready && reader.chapterIndex()==target && reader.lastRestoreCode==null){"NAVIGATION_NOT_READY"};outcome="success"}
        catch(e:Exception){if(e !is kotlinx.coroutines.CancellationException){outcome="failed";error=(e as? RestoreFailure)?.code?.name ?: "NAVIGATION_FAILED"};throw e}
        finally{
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable){kotlinx.coroutines.withTimeoutOrNull(500){runCatching{edges(false)}}}
            busy=false
            val root=runCatching{BookStore.readAtomic(file)}.getOrElse{JSONObject().put("schema",1).put("events",JSONArray())}
            val rows=root.getJSONArray("events");rows.put(JSONObject().put("session",reader.id).put("book",com.fantadrom.jjreader.text.TextModel.hash(reader.book.id.toByteArray()).take(16)).put("initiator",initiator.name).put("from",origin).put("target",target).put("entry",if(initiator in setOf(Initiator.NOTE,Initiator.TOC,Initiator.LINK))"locator"else if(end)"end"else"start").put("revision",reader.currentRevision).put("outcome",outcome).put("ack",outcome=="success").put("durationMs",(System.nanoTime()-start)/1_000_000).put("errorCode",error))
            while(rows.length()>80)rows.remove(0);BookStore.writeAtomic(file,root)
        }
    }
    suspend fun boundary(target:Int,end:Boolean,initiator:Initiator)=run(initiator,target,end){reader.navigateBoundary(target,end,if(initiator==Initiator.TTS)ReadingTrace.Source.RESTORE else ReadingTrace.Source.NATIVE)}
}
