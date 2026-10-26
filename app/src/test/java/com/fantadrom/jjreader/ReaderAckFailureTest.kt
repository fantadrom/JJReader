@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ReaderAckFailureTest {
    private val book=Book(UUID.randomUUID().toString(),"unused",File("unused"),(0..7).map{Chapter("EPUB/x$it.xhtml","unused","АБВГД".repeat(1000))})
    private fun anchor(chapter:Int,start:Int)=JSONObject().put("chapter",chapter).put("start",start).put("end",start+2)
        .put("quote",TextModel.slice(book.chapters[chapter].text,start,start+2)).put("bookId",book.id)
        .put("textHash",TextModel.hash(book.chapters[chapter].text.toByteArray()))
    private inner class Port(var index:Int=3):ReaderPort {
        var request="";var document=0;var phase="failed";var reason="ANCHOR_NOT_VISIBLE"
        var viewport=false;var hidden=false;var pendingForever=false;var noAck=false;var wrongRequest=false
        var visibleStart=2210;var height=24;val goHistory=mutableListOf<Int>()
        private fun context()=JSONObject().put("url","https://readium_package/"+book.chapters[index].href)
            .put("documentId","$index-$document").put("nativeReady",true)
        private fun geometry(shown:Boolean)=context().put("viewportWidth",1000).put("viewportHeight",height)
            .put("scrollY",500).put("layoutReady",height>0).put("layoutStable",height>0).put("shown",shown)
            .put("firstRect",JSONObject().put("top",if(shown)0 else 40).put("bottom",if(shown)23 else 63).put("height",23).put("width",12))
        override fun chapterIndex()=index
        override suspend fun text()=book.chapters[index].text
        override suspend fun goResource(index:Int):Boolean{this.index=index;document++;goHistory+=index;return true}
        override suspend fun go(locator:Locator){index=book.chapters.indexOfFirst{it.href==locator.href.toString()};document++}
        override fun locator(anchor:JSONObject)=checkNotNull(Locator.fromJSON(JSONObject().put("href",book.chapters[index].href).put("type","application/xhtml+xml")))
        override suspend fun call(expression:String):JSONObject?=when {
            expression=="JJ.context()"->context()
            expression.startsWith("({ok:JJ.requestRestore(")->{request=expression.substringAfter("requestRestore(\"").substringBefore('"');JSONObject().put("ok",true)}
            expression=="JJ.restoreStatus()"->if(noAck)null else geometry(phase=="confirmed" && !hidden).put("id",if(wrongRequest)"other-request"else request)
                .put("phase",if(pendingForever)"pending"else phase).put("reason",reason)
            expression=="JJ.viewport()"->if(!viewport)null else geometry(true).put("anchor",anchor(index,visibleStart)).put("first",visibleStart).put("last",visibleStart+20)
            else->error("Unexpected bridge expression")
        }
    }
    @Test fun acceptedNoteFalseAckCommitsVerifiedVisibleX6InsteadOfKeepingX3()=runTest {
        val ctx=RuntimeEnvironment.getApplication();val dir=File(ctx.filesDir,"books-v1/${book.id}").apply{mkdirs()}
        try {
            val notes=NoteStore(ctx,book.id);notes.data.put("positionV2",anchor(3,1310));notes.save()
            val trace=ReadingTrace(File(dir,"trace.json"));val port=Port().apply{viewport=true;phase="confirmed";hidden=true}
            val reader=ReaderSession(book,notes.data,notes::save,port,trace)
            reader.navigateTo(anchor(6,2262))
            assertTrue(reader.ready);assertEquals(RestoreCode.ANCHOR_NOT_VISIBLE,reader.lastRestoreCode)
            val read=NoteStore(ctx,book.id).data.getJSONObject("positionV2")
            assertEquals(6,read.getInt("chapter"));assertEquals(2210,read.getInt("start")) // actual visible point, never the unacknowledged target
            val events=trace.json().getJSONArray("events");val failure=(0 until events.length()).map{events.getJSONObject(it)}.first{it.optString("failureCode")=="ANCHOR_NOT_VISIBLE"}
            assertTrue(failure.has("requestId"));assertEquals(24,failure.getJSONObject("geometry").getInt("viewportHeight"));assertEquals(40,failure.getJSONObject("geometry").getJSONObject("firstRect").getInt("top"))
            val reopened=NoteStore(ctx,book.id);val nextPort=Port(6).apply{phase="confirmed"}
            ReaderSession(book,reopened.data,reopened::save,nextPort).restoreInitial()
            assertEquals(6,nextPort.index);assertEquals(6,reopened.data.getJSONObject("positionV2").getInt("chapter"))
        }finally{dir.deleteRecursively()}
    }
    @Test fun failedInitialCanLaterCaptureUserResourceX5WithoutReplayingInitialX3()=runTest {
        val data=JSONObject().put("positionV2",anchor(3,1310));val port=Port();val reader=ReaderSession(book,data,{},port)
        try{reader.restoreInitial();fail()}catch(e:RestoreFailure){assertEquals(RestoreCode.ANCHOR_NOT_VISIBLE,e.code)}
        assertFalse(reader.ready);assertEquals(ReaderSession.Phase.RECOVERING,reader.phase);assertFalse(reader.shouldRestoreInitial)
        port.index=5;port.document++;port.visibleStart=705;port.viewport=true
        reader.capture();assertTrue(reader.ready);assertEquals(5,data.getJSONObject("positionV2").getInt("chapter"))
        val restored=JSONObject(data.toString());val nextPort=Port(5).apply{phase="confirmed"}
        ReaderSession(book,restored,{},nextPort).restoreInitial()
        assertEquals(5,nextPort.index);assertTrue(port.goHistory.isEmpty())
    }
    @Test fun zeroLayoutCannotBecomeReadyUntilAVisibleStableSnapshotIsValidated()=runTest {
        val data=JSONObject().put("positionV2",anchor(3,1310));val port=Port().apply{height=0;viewport=true;reason="LAYOUT_TIMEOUT"}
        val reader=ReaderSession(book,data,{},port)
        try{reader.navigateTo(anchor(6,2262));fail()}catch(e:RestoreFailure){assertEquals(RestoreCode.LAYOUT_TIMEOUT,e.code)}
        assertFalse(reader.ready);assertEquals(3,data.getJSONObject("positionV2").getInt("chapter"))
        port.height=32;reader.capture();assertTrue(reader.ready);assertEquals(6,data.getJSONObject("positionV2").getInt("chapter"))
    }
    @Test fun missingAckHasBoundedTimeoutAndDoesNotPermanentlyBlockReading()=runTest {
        val data=JSONObject().put("positionV2",anchor(3,1310));val port=Port().apply{noAck=true};val reader=ReaderSession(book,data,{},port)
        try{reader.navigateTo(anchor(6,2262));fail()}catch(e:RestoreFailure){assertEquals(RestoreCode.ACK_TIMEOUT,e.code)}
        assertTrue(testScheduler.currentTime in 6000..8000);assertEquals(ReaderSession.Phase.RECOVERING,reader.phase)
        port.viewport=true;reader.capture();assertEquals(6,data.getJSONObject("positionV2").getInt("chapter"))
    }
    @Test fun wrongRequestNeverCommitsTargetAndCancellationCannotFreezeNextUserView()=runTest {
        val data=JSONObject().put("positionV2",anchor(3,1310));val port=Port().apply{wrongRequest=true};val reader=ReaderSession(book,data,{},port)
        try{reader.navigateTo(anchor(6,2262));fail()}catch(e:RestoreFailure){assertEquals(RestoreCode.STALE_DOM,e.code)}
        assertEquals(3,data.getJSONObject("positionV2").getInt("chapter"))
        port.wrongRequest=false;port.pendingForever=true
        val pending=launch{reader.restoreInitial()};runCurrent()
        reader.preparePause();pending.cancelAndJoin()
        assertEquals(ReaderSession.Phase.RECOVERING,reader.phase)
        port.index=5;port.document++;port.viewport=true;port.visibleStart=705
        reader.capture();assertTrue(reader.ready);assertEquals(5,data.getJSONObject("positionV2").getInt("chapter"))
    }
    @Test fun geometryTraceAllowListNeverExportsPrivateStringsOrPayloads() {
        val dir=java.nio.file.Files.createTempDirectory("ack-trace").toFile()
        try {
            val trace=ReadingTrace(File(dir,"trace.json"));trace.record(ReadingTrace.Event.REJECT,ReadingTrace.Source.NOTE,"activity",
                geometry=JSONObject().put("viewportHeight",24).put("title","PRIVATE").put("url","https://SECRET").put("firstRect",JSONObject().put("top",40).put("quote","PRIVATE")),failureCode=RestoreCode.ANCHOR_NOT_VISIBLE,requestId="request")
            val json=trace.json();assertEquals(3,json.getInt("schema"));assertTrue(json.toString().contains("ANCHOR_NOT_VISIBLE"))
            listOf("PRIVATE","SECRET","https://","quote","title").forEach{assertFalse(json.toString().contains(it))}
        }finally{dir.deleteRecursively()}
    }
}
