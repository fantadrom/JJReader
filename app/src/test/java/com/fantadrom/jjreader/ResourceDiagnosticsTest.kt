package com.fantadrom.jjreader

import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.tts.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ResourceDiagnosticsTest {
    @Test fun traceSurvivesMultipleInstancesAndBoundsDataWithoutPrivateFields() {
        val dir=Files.createTempDirectory("reading-trace").toFile();val file=dir.resolve("trace.json")
        try {
            val first=ReadingTrace(file);first.record(ReadingTrace.Event.CREATE,ReadingTrace.Source.LIFECYCLE,"activity-1",book="PRIVATE title /path")
            val session=first.json().getString("sessionId")
            val next=ReadingTrace(file);next.record(ReadingTrace.Event.DOM,ReadingTrace.Source.NOTE,"activity-2","reader-2",document="https://private/title?secret",target=1,dom=0,accepted=false)
            val rows=first.json().getJSONArray("events");listOf("PRIVATE","/path","https://","secret","signature").forEach{assertFalse(first.json().toString().contains(it))};assertEquals(2,rows.length());assertEquals(session,next.json().getString("sessionId"))
            // An old Activity rewriting the same old snapshot after a newer writer must be visible.
            first.record(ReadingTrace.Event.STORE_WRITE,ReadingTrace.Source.SESSION,"activity-1","old",target=0,diskChapter=0)
            next.record(ReadingTrace.Event.STORE_WRITE,ReadingTrace.Source.SESSION,"activity-2","new",target=1,diskChapter=1)
            first.record(ReadingTrace.Event.STORE_WRITE,ReadingTrace.Source.SESSION,"activity-1","old",target=0,diskChapter=0)
            assertEquals(5,next.json().getJSONArray("events").length())
            repeat(300){next.record(ReadingTrace.Event.GO,ReadingTrace.Source.NOTE,"activity-2",revision=it.toLong(),target=it%3)}
            assertEquals(256,first.json().getJSONArray("events").length())
            val text=first.json().toString();listOf("PRIVATE","/path","https://","secret","signature").forEach{assertFalse(text.contains(it))}
            next.begin();assertNotEquals(session,first.json().getString("sessionId"));assertEquals(0,first.json().getJSONArray("events").length())
        }finally{dir.deleteRecursively()}
    }
    @Test fun storeObserverReadsTheActualAtomicFileAfterEveryWriter() {
        val ctx=RuntimeEnvironment.getApplication();val id=UUID.randomUUID().toString();val dir=java.io.File(ctx.filesDir,"books-v1/$id").apply{mkdirs()}
        try {
            val store=NoteStore(ctx,id);var observed=false
            store.onSaved={source,data,disk->assertEquals(NoteStore.Source.SESSION,source);assertEquals(1,disk!!.getJSONObject("positionV2").getInt("chapter"));assertEquals(data.toString(),disk.toString());observed=true}
            store.data.put("positionV2",JSONObject().put("chapter",1).put("start",90));store.save(NoteStore.Source.SESSION)
            assertTrue(observed);assertEquals(1,NoteStore(ctx,id).data.getJSONObject("positionV2").getInt("chapter"))
        }finally{dir.deleteRecursively()}
    }
    @Test fun resourceStyleIsPersistentIdempotentAndXmlSafeBeforeAnyBridge() {
        val input="<html xmlns='http://www.w3.org/1999/xhtml'><head></head><body><p>Текст</p></body></html>"
        val first=SafeHtml.transform(input,"application/xhtml+xml");val second=SafeHtml.transform(first,"application/xhtml+xml")
        val doc=XhtmlPipelineTest.strictXml(second)
        val styles=doc.getElementsByTagName("style");assertEquals(1,styles.length)
        assertTrue(styles.item(0).textContent.contains("max-width:none!important"));assertTrue(second.contains("connect-src 'none'"))
    }
    @Test fun responseSummaryExposesOnlyKnownFormatAndStatusCategories() {
        val summary=EdgeProtocol.response(emptyMap(),"{\"context\":{\"synthesis\":{\"status\":\"PRIVATE quoted input\"}},\"secret\":\"URL TOKEN\"}")
        assertEquals("json",summary.format);assertEquals("other",summary.status);assertTrue(summary.hasStatus);assertFalse(summary.hasError)
        assertEquals("absent",EdgeProtocol.response(emptyMap(),"{\"context\":{}}").status)
        assertEquals("success",EdgeProtocol.response(emptyMap(),"{\"status\":\"Success\"}").status)
    }
    @Test fun routeComparisonMakesFourSequentialUncachedRequestsWithSameProfileAndNoFallback() = runBlocking {
        val servers=listOf(MockWebServer(),MockWebServer());servers.forEach{it.start()}
        val clients=servers.mapIndexed{i,s->EdgeClient(s.url("/edge").toString(),route=EdgeRoute.entries[i])}
        val dir=Files.createTempDirectory("route-compare").toFile();val diagnostics=SpeechDiagnostics();val played=mutableListOf<String>()
        fun response()=MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onClosing(w:WebSocket,code:Int,reason:String){w.close(code,null)}
            override fun onMessage(w:WebSocket,text:String){if(text.startsWith("X-RequestId:")){
                w.send("Path:response\r\n\r\n{\"context\":{\"synthesis\":{\"status\":\"Success\"}}}")
                val h="Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray();w.send(ByteString.of(*(byteArrayOf(0,h.size.toByte())+h+ByteArray(200))))
                w.send("Path:turn.end\r\n\r\n")
            }}
        })
        try {
            servers.forEach{it.enqueue(response());it.enqueue(response())}
            suspend fun fetch(client:EdgeClient,text:String,voice:Voice,a:SpeechDiagnostics.Attempt):java.io.File {
                assertEquals(VoiceProbe.TEXT,text);return dir.resolve(UUID.randomUUID().toString()+".mp3").apply{writeBytes(client.synthesize(text,voice,a))}
            }
            VoiceProbe(diagnostics,{t,v,a->fetch(clients[0],t,v,a)},{_,a->a.record("playback");played+=a.id},{},
                {t,v,a->fetch(clients[1],t,v,a)}).run(-1,true)
            assertEquals(listOf(2,2),servers.map{it.requestCount});assertEquals(4,played.size);assertTrue(dir.listFiles()!!.isEmpty())
            val root=JSONObject(diagnostics.text());assertEquals("routeComparison",root.getString("purpose"));val rows=root.getJSONArray("attempts")
            repeat(4){val row=rows.getJSONObject(it);assertEquals(if(it<2)"current"else"readest",row.getString("route"));assertEquals(1,row.getInt("maxAttempts"));assertEquals("full-ru-default-v1",row.getString("ssmlProfile"));assertEquals("success",row.getString("responseStatus"));assertEquals("completed",row.getString("playback"))}
            servers.forEachIndexed{i,server->repeat(2){val url=server.takeRequest().requestUrl!!;assertNotNull(url.queryParameter(EdgeRoute.entries[i].tokenParameter));assertNull(url.queryParameter(EdgeRoute.entries[1-i].tokenParameter))}}
            listOf("TrustedClientToken","Ocp-Apim","https://","Cookie",VoiceProbe.TEXT).forEach{assertFalse(diagnostics.text().contains(it))}
        }finally{clients.forEach{it.close()};servers.forEach{it.shutdown()};dir.deleteRecursively()}
    }
}
