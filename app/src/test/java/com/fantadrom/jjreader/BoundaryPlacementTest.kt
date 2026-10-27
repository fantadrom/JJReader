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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class BoundaryPlacementTest {
    private val book=Book("book", "Unused",File("unused"),listOf(Chapter("s0.xhtml","","Текст. ".repeat(120)),Chapter("s1.xhtml","","Вторая глава. ".repeat(40)),Chapter("s2.xhtml","","")))
    private fun anchor(index:Int,start:Int)=JSONObject().put("chapter",index).put("start",start).put("end",start+1).put("quote",TextModel.slice(book.chapters[index].text,start,start+1)).put("bookId",book.id)
    private inner class Port:ReaderPort {
        override val sections=true
        var index=0;var document=0;var request="";var requested:BoundaryPlacement?=null
        var viewportAnchor:JSONObject?=null;var viewportPlacement:BoundaryPlacement?=null;var visual=false
        var ackGate:CompletableDeferred<Unit>?=null;var staleVisual=false
        override fun chapterIndex()=index
        private fun state()=JSONObject().put("url","https://readium_package/s$index.xhtml").put("documentId","$index-$document").put("nativeReady",true).put("documentReady",true).put("fontReady",true).put("resourcesReady",true).put("viewportWidth",430).put("viewportHeight",700).put("layoutReady",true).put("layoutStable",true).put("shown",true)
        override suspend fun context(index:Int)=state()
        override suspend fun text()=book.chapters[index].text
        override suspend fun goResource(index:Int):Boolean{this.index=index;document++;return true}
        override suspend fun go(locator:Locator){}
        override fun locator(anchor:JSONObject)=checkNotNull(Locator.fromJSON(JSONObject().put("href","s$index.xhtml").put("type","application/xhtml+xml").put("locations",JSONObject())))
        override fun nativeLocator()=locator(JSONObject())
        override suspend fun call(expression:String):JSONObject?=when {
            expression.startsWith("({ok:JJ.requestRestore(")||expression.startsWith("({ok:JJ.requestVisualRestore(")->{
                request=expression.substringAfter("Restore(\"").substringBefore('"')
                requested=BoundaryPlacement.entries.firstOrNull{expression.contains("\"kind\":\"${it.name}\"")};JSONObject().put("ok",true)
            }
            expression=="JJ.restoreStatus()"||expression=="JJ.visualStatus()"->{
                val s=state().put("id",request).put("phase","confirmed");if(staleVisual&&expression=="JJ.visualStatus()")s.put("documentId","old")
                val gate=ackGate;ackGate=null;gate?.await();s
            }
            expression=="JJ.contentState()"->JSONObject().put("lastMedia",false)
            expression=="JJ.viewport()"->if(visual)state().put("visual",true).put("visualEnd",false).put("entryPlacement",viewportPlacement?.saved())else viewportAnchor?.let{state().put("anchor",JSONObject(it.toString())).put("entryPlacement",viewportPlacement?.saved())}
            else->error("Unexpected bridge call: $expression")
        }
    }
    @Test fun semanticEntrySurvivesSixRecreationsAndCaptureThenManualMoveReleasesIt()=runTest {
        for(kind in listOf(BoundaryPlacement.RESOURCE_START,BoundaryPlacement.END_PAGE)){
            val data=JSONObject();var port=Port();var reader=ReaderSession(book,data,{},port)
            reader.navigateBoundary(0,kind,ReadingTrace.Source.NATIVE)
            assertEquals(kind,port.requested);assertEquals(kind,BoundaryPlacement.read(data.getJSONObject("positionV2").optJSONObject("entryPlacement")))
            val stored=JSONObject(data.toString()).put("locator",JSONObject().put("href","s0.xhtml").put("type","application/xhtml+xml").put("locations",JSONObject().put("cssSelector","#end")))
            val initial=RenditionAdapter(book,sections=true).initialLocator(stored)!!.toJSON()
            assertEquals("s0.xhtml",initial.getString("href"));assertFalse(initial.optJSONObject("locations")?.has("cssSelector")==true)
            repeat(6){
                // The visible first line differs from the semantic end; capture must retain entry.
                port.viewportAnchor=anchor(0,200);port.viewportPlacement=kind;reader.capture()
                port=Port();reader=ReaderSession(book,data,{},port);reader.restoreInitial()
                assertTrue(reader.ready);assertEquals(kind,port.requested)
            }
            port.viewportAnchor=anchor(0,300);port.viewportPlacement=null;reader.capture()
            assertFalse(data.getJSONObject("positionV2").has("entryPlacement"));assertEquals(300,data.getJSONObject("positionV2").getInt("start"))
            port=Port();reader=ReaderSession(book,data,{},port);reader.restoreInitial();assertNull(port.requested)
        }
    }
    @Test fun visualEntryRetainsResourceAndSemanticPlacementWithoutTextOrPixels()=runTest {
        val data=JSONObject();var port=Port();var reader=ReaderSession(book,data,{},port)
        reader.navigateBoundary(2,BoundaryPlacement.END_PAGE,ReadingTrace.Source.NATIVE)
        assertEquals("visual",data.getString("positionKind"));assertEquals(2,data.getJSONObject("visualPosition").getInt("chapter"))
        port.visual=true;port.viewportPlacement=BoundaryPlacement.END_PAGE;reader.capture()
        assertEquals(BoundaryPlacement.END_PAGE,BoundaryPlacement.read(data.getJSONObject("visualPosition").optJSONObject("entryPlacement")))
        assertFalse(data.toString().contains("scrollY"))
        port=Port();reader=ReaderSession(book,data,{},port);reader.restoreInitial();assertTrue(reader.ready);assertEquals(2,port.index);assertEquals(BoundaryPlacement.END_PAGE,port.requested)
        // Old visual records remain readable; an unknown future marker is ignored.
        data.getJSONObject("visualPosition").put("entryPlacement",JSONObject().put("schema",42).put("kind","END_PAGE"))
        port=Port();reader=ReaderSession(book,data,{},port);reader.restoreInitial();assertNull(port.requested)
    }
    @Test fun lateBoundaryAckCannotReplaceNewNoteOrCommitItsPlacement()=runTest {
        val data=JSONObject();val port=Port();val reader=ReaderSession(book,data,{},port)
        val gate=CompletableDeferred<Unit>();port.ackGate=gate
        val old=launch{reader.navigateBoundary(0,BoundaryPlacement.END_PAGE,ReadingTrace.Source.NATIVE)};runCurrent()
        reader.navigateTo(anchor(1,40));gate.complete(Unit);old.join()
        assertTrue(old.isCancelled);assertTrue(reader.ready);assertEquals(1,data.getJSONObject("positionV2").getInt("chapter"));assertFalse(data.getJSONObject("positionV2").has("entryPlacement"))
    }
    @Test fun wrongVisualDocumentNeverCommitsAndGestureEndDoesNotBecomeTapPage()=runTest {
        val data=JSONObject();val port=Port();val reader=ReaderSession(book,data,{},port)
        reader.navigateBoundary(0,BoundaryPlacement.END_TOP,ReadingTrace.Source.NATIVE);assertNull(port.requested);assertFalse(data.getJSONObject("positionV2").has("entryPlacement"))
        val durable=data.toString();port.staleVisual=true
        try{reader.navigateBoundary(2,BoundaryPlacement.END_PAGE,ReadingTrace.Source.NATIVE);fail("Stale document ACK")}catch(e:RestoreFailure){assertEquals(RestoreCode.STALE_DOM,e.code)}
        assertEquals(durable,data.toString())
    }
    @Test fun controllerRecordsDistinctEntriesAndWhitelistsBoundaryMetrics()=runTest {
        val data=JSONObject();val port=Port();val reader=ReaderSession(book,data,{},port)
        val file=File(System.getProperty("java.io.tmpdir"),"nav-${UUID.randomUUID()}.json")
        try {
            val nav=SectionNavigation(reader,file)
            nav.boundary(1,BoundaryPlacement.RESOURCE_START,SectionNavigation.Initiator.TAP,JSONObject().put("lineHeight",26).put("freeBelow",31).put("quote","private").put("url","private"))
            nav.boundary(0,BoundaryPlacement.END_PAGE,SectionNavigation.Initiator.TAP)
            nav.boundary(1,BoundaryPlacement.END_TOP,SectionNavigation.Initiator.GESTURE)
            val rows=BookStore.readAtomic(file).getJSONArray("events");assertEquals("TAP_FORWARD_START",rows.getJSONObject(0).getString("entry"));assertEquals("TAP_BACK_END_PAGE",rows.getJSONObject(1).getString("entry"));assertEquals("GESTURE_BACK_END",rows.getJSONObject(2).getString("entry"));assertFalse(rows.toString().contains("private"))
        }finally{file.delete()}
    }
}
