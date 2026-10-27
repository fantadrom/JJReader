@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderPositionTest {
    private val book = Book("anonymous", "unused", File("unused"), (0..1).map { Chapter("EPUB/x$it.xhtml", "unused", "АБВГД".repeat(100)) })
    private fun anchor(index: Int, start: Int) = JSONObject().put("chapter", index).put("start", start).put("end", start+2)
        .put("quote", TextModel.slice(book.chapters[index].text,start,start+2)).put("bookId",book.id)
        .put("textHash",TextModel.hash(book.chapters[index].text.toByteArray()))
    private inner class Port(var index: Int = 0): ReaderPort {
        var restoreId=""; var document=0; var target=0
        var contextGate: CompletableDeferred<Unit>? = null
        var viewportGate: CompletableDeferred<Unit>? = null
        var viewportAnchor: JSONObject? = null
        var onPresent:(suspend(Int)->Unit)?=null
        override suspend fun present(index:Int,operation:Long,document:String?){onPresent?.invoke(index)}
        override fun chapterIndex()=index
        private fun context()=JSONObject().put("url","https://readium_package/"+book.chapters[index].href)
            .put("documentId","$index-$document").put("nativeReady",true).put("viewportWidth",430).put("viewportHeight",650).put("layoutReady",true).put("layoutStable",true).put("shown",true)
        override suspend fun text()=book.chapters[index].text
        override suspend fun goResource(index:Int):Boolean {this.index=index;document++;return true}
        override suspend fun go(locator:Locator) { index=book.chapters.indexOfFirst{it.href==locator.href.toString()};document++ }
        override fun locator(anchor:JSONObject):Locator=checkNotNull(Locator.fromJSON(JSONObject().put("href",book.chapters[index].href).put("type","application/xhtml+xml").put("locations",JSONObject())))
        override suspend fun call(expression:String):JSONObject? = when {
            expression=="JJ.context()" -> {val saved=context();val gate=contextGate;contextGate=null;gate?.await();saved}
            expression.startsWith("({ok:JJ.requestRestore(") -> {
                restoreId=expression.substringAfter("requestRestore(\"").substringBefore('"')
                target=expression.substringAfter("\",").substringBefore(',').toInt();JSONObject().put("ok",true)
            }
            expression=="JJ.restoreStatus()" -> context().put("id",restoreId).put("shown",true).put("phase","confirmed")
            expression=="JJ.viewport()" -> {
                val saved=viewportAnchor?.let{context().put("anchor",JSONObject(it.toString()))}
                val gate=viewportGate;viewportGate=null;gate?.await();saved
            }
            else -> error("Unexpected bridge call")
        }
    }
    @Test fun presentationIsRequestedOnlyAfterAckAndDurableTargetCommit()=runTest {
        val data=JSONObject();val port=Port();var shown=0
        lateinit var reader:ReaderSession
        reader=ReaderSession(book,data,{},port)
        port.onPresent={index->assertTrue(reader.ready);assertEquals(index,data.getJSONObject("positionV2").getInt("chapter"));assertEquals(90,data.getJSONObject("positionV2").getInt("start"));shown++}
        reader.navigateTo(anchor(1,90));assertEquals(1,shown)
        try{reader.navigateTo(anchor(0,20).put("textHash","wrong"));fail()}catch(_:IllegalStateException){}
        assertEquals(1,shown)
    }
    @Test fun returnPlaceUsesOriginalCoordinatesAndRejectsMismatchedBook()=runTest {
        val data=JSONObject();val port=Port();val reader=ReaderSession(book,data,{},port)
        val previous=anchor(0,90);reader.navigateTo(previous);reader.navigateTo(anchor(1,160))
        reader.restorePlace(previous);assertTrue(reader.ready);assertEquals(0,port.index);assertEquals(90,data.getJSONObject("positionV2").getInt("start"))
        try{reader.restorePlace(JSONObject(previous.toString()).put("bookId","different"));fail("Wrong book")}catch(_:IllegalStateException){}
        assertEquals(ReaderSession.Phase.RECOVERING,reader.phase)
    }
    @Test fun verifiedLinkRetryClearsEarlierTargetFailure()=runTest {
        val data=JSONObject();val port=Port();val reader=ReaderSession(book,data,{},port)
        reader.navigateTo(anchor(0,20));port.viewportAnchor=anchor(0,20)
        reader.navigateTo(anchor(1,30).put("textHash","wrong"));assertEquals(RestoreCode.ANCHOR_HASH,reader.lastRestoreCode)
        port.viewportAnchor=anchor(1,60)
        val locator=checkNotNull(Locator.fromJSON(JSONObject().put("href",book.chapters[1].href).put("type","application/xhtml+xml")))
        reader.confirmLink(1,locator)
        assertTrue(reader.ready);assertNull(reader.lastRestoreCode);assertEquals(1,data.getJSONObject("positionV2").getInt("chapter"));assertEquals(60,data.getJSONObject("positionV2").getInt("start"))
    }
    @Test fun repeatedCrossResourceJumpsSurviveImmediateAndDelayedRecreationWithoutViewportAnchor() = runTest {
        for (wait in listOf(0L,2500L)) {
            var durable=JSONObject();var data=JSONObject();var port=Port()
            var reader=ReaderSession(book,data,{durable=JSONObject(data.toString())},port)
            repeat(3){cycle->
                for(index in 0..1){
                    val target=anchor(index,40+cycle*20)
                    reader.navigateTo(target)
                    assertTrue(reader.ready);assertEquals(index,durable.getJSONObject("positionV2").getInt("chapter"))
                    // No periodic capture succeeds, including onPause. Target must already be durable.
                    assertNull(reader.capture());advanceTimeBy(wait)
                    reader.freeze();data=JSONObject(durable.toString())
                    port=Port(data.getJSONObject("positionV2").getInt("chapter"))
                    reader=ReaderSession(book,data,{durable=JSONObject(data.toString())},port)
                    reader.restoreInitial()
                    assertEquals(index,port.index);assertEquals(target.getInt("start"),port.target)
                    assertEquals(index,durable.getJSONObject("positionV2").getInt("chapter"))
                }
            }
        }
    }
    @Test fun lateInitialRestoreCannotUnfreezeOrOverwriteNewNavigation() = runTest {
        val data=JSONObject().put("positionV2",anchor(0,20));val saved=mutableListOf<Int>();val port=Port()
        val reader=ReaderSession(book,data,{saved+=data.getJSONObject("positionV2").getInt("chapter")},port)
        val gate=CompletableDeferred<Unit>();port.contextGate=gate
        val old=launch{reader.restoreInitial()};runCurrent()
        reader.navigateTo(anchor(1,90));assertTrue(reader.ready)
        gate.complete(Unit);old.join()
        assertTrue(old.isCancelled);assertTrue(reader.ready);assertEquals(1,data.getJSONObject("positionV2").getInt("chapter"))
        assertEquals(listOf(1),saved)
    }
    @Test fun delayedCaptureCannotCommitPreviousResourceAfterNewJump() = runTest {
        val data=JSONObject();val port=Port();val reader=ReaderSession(book,data,{},port)
        reader.navigateTo(anchor(0,20));port.viewportAnchor=anchor(0,20)
        val gate=CompletableDeferred<Unit>();port.viewportGate=gate
        val old=launch{reader.capture()};runCurrent();port.viewportAnchor=null
        reader.navigateTo(anchor(1,100));gate.complete(Unit);old.join()
        assertTrue(old.isCancelled);assertEquals(1,data.getJSONObject("positionV2").getInt("chapter"));assertTrue(reader.ready)
    }
    @Test fun freshNoteStoreReadsConfirmedX2FromAtomicFileWithoutPeriodicCapture() = runTest {
        val context=org.robolectric.RuntimeEnvironment.getApplication()
        val id=java.util.UUID.randomUUID().toString();val ownBook=book.copy(id=id)
        val dir=File(context.filesDir,"books-v1/$id").apply{mkdirs()}
        try {
            val notes=com.fantadrom.jjreader.notes.NoteStore(context,id)
            val port=Port();val reader=ReaderSession(ownBook,notes.data,notes::save,port)
            reader.navigateTo(anchor(0,20).put("bookId",id))
            reader.navigateTo(anchor(1,90).put("bookId",id))
            reader.freeze() // destroy before any successful viewport capture
            val reopened=com.fantadrom.jjreader.notes.NoteStore(context,id)
            assertEquals(1,reopened.data.getJSONObject("positionV2").getInt("chapter"))
            val nextPort=Port(1);val next=ReaderSession(ownBook,reopened.data,reopened::save,nextPort)
            next.restoreInitial();assertEquals(1,nextPort.index);assertEquals(90,nextPort.target)
        }finally{dir.deleteRecursively()}
    }
    @Test fun invalidOrUnacknowledgedTargetsNeverBecomeDurable() = runTest {
        val data=JSONObject();val port=Port();val reader=ReaderSession(book,data,{},port)
        reader.navigateTo(anchor(0,20));val saved=data.toString()
        try{reader.navigateTo(anchor(1,30).put("textHash","wrong"));fail()}catch(_:IllegalStateException){}
        assertEquals(saved,data.toString())
        val gate=CompletableDeferred<Unit>();port.contextGate=gate
        val pending=launch{reader.navigateTo(anchor(1,90))};runCurrent();reader.freeze();gate.complete(Unit);pending.join()
        assertTrue(pending.isCancelled);assertFalse(reader.ready);assertEquals(saved,data.toString())
    }
}
