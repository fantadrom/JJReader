@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.fantadrom.jjreader

import com.fantadrom.jjreader.tts.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.SocketException
import java.nio.file.Files

class SpeechRetryTest {
    private val items = (1..3).map { Phrase(it * 2, it * 2 + 1, "private novel $it") }
    private fun rows(d: SpeechDiagnostics) = JSONObject(d.text()).getJSONArray("attempts")
    @Test fun thirdPhraseRetriesWithoutReplayingTwoCompletedPhrasesAndCacheIsNotNetwork() = runTest {
        val dir = Files.createTempDirectory("retry").toFile(); val d = SpeechDiagnostics()
        val heard = mutableListOf<Int>(); val messages = mutableListOf<Int>(); var requests = 0
        val cache = PhraseAudioCache(dir, onRetry = { messages += it }) { text, _, a ->
            requests++; a.record("opened", http = 101)
            if (text == items[2].text && a.number == 1) throw EdgeFailure("IO_SocketException", "opened", SocketException("PRIVATE"))
            a.record("firstAudio"); a.audio(200); a.record("synthesized"); ByteArray(200)
        }
        try {
            val queue = SpeechQueue(this, d, { p,v,a -> cache.get(p,v,a) }, { _, a, started ->
                started(); heard += rows(d).let { all -> (0 until all.length()).map { all.getJSONObject(it) }.first { it.getString("id") == a.id }.getInt("block") }
            }, {}, {}, { true }, { true }, {}, StandardTestDispatcher(testScheduler))
            queue.start(items, Voice.DMITRY); advanceUntilIdle()
            assertEquals(listOf(1,2,3), heard); assertEquals(4, requests); assertEquals(listOf(2), messages)
            assertEquals(SpeechQueue.State.COMPLETED, queue.state)
            val third = (0 until rows(d).length()).map { rows(d).getJSONObject(it) }.filter { it.getInt("block") == 3 }
            assertEquals(listOf(1,2), third.map { it.getInt("networkAttempt") }); assertEquals("error", third[0].getString("outcome"))
            assertEquals("completed", third[1].getString("playback")); assertEquals(200, third[1].getInt("audioBytes"))
            assertTrue(third[1].has("openedMs") && third[1].has("firstAudioMs") && third[1].has("synthesizedMs") && third[1].has("completedMs"))
            val hit = d.attempt(Voice.DMITRY); cache.get(items[2],Voice.DMITRY,hit)
            assertEquals(4, requests); assertEquals(0, rows(d).getJSONObject(rows(d).length()-1).getInt("networkAttempt"))
            listOf("private novel", "PRIVATE", "SSML", "TrustedClientToken").forEach { assertFalse(d.text().contains(it)) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun exhaustedBudgetManualResumeStartsAtUnreadPhrase() = runTest {
        val dir = Files.createTempDirectory("exhaust").toFile(); var bad = true; var requests = 0; var heard = 0
        val d = SpeechDiagnostics(); val cache = PhraseAudioCache(dir) { _,_,_ -> requests++; if(bad) throw EdgeFailure("IO_SocketException"); ByteArray(200) }
        try {
            val q = SpeechQueue(this,d,{p,v,a->cache.get(p,v,a)},{_,_,s->s();heard++},{},{},{true},{true},{},StandardTestDispatcher(testScheduler))
            q.start(items.take(1), Voice.DMITRY); advanceUntilIdle()
            assertEquals(3,requests); assertEquals(0,q.cursor); assertEquals(SpeechQueue.State.ERROR,q.state)
            assertTrue(dir.listFiles()!!.isEmpty()); bad=false; q.resume(); advanceUntilIdle()
            assertEquals(4,requests); assertEquals(1,heard); assertEquals(SpeechQueue.State.COMPLETED,q.state)
        } finally { dir.deleteRecursively() }
    }
    @Test fun protocolTlsAuthAndLongRetryAfterNeverLoop() = runTest {
        for (code in listOf("FRAME_HEADER","FRAME_SHORT","HTTP_401","HTTP_403","TLS","NO_AUDIO","SEND_REJECTED","CLOSED_1008")) {
            val dir=Files.createTempDirectory("no-retry").toFile();var requests=0
            try { val cache=PhraseAudioCache(dir){_,_,_->requests++;throw EdgeFailure(code)}
                try { cache.get(items[0],Voice.DMITRY,SpeechDiagnostics().attempt(Voice.DMITRY)); fail() } catch(e:EdgeFailure){assertEquals(code,e.code)}
                assertEquals(1,requests)
            } finally {dir.deleteRecursively()}
        }
        assertEquals(4000L,PhraseAudioCache.retryDelay(EdgeFailure("HTTP_429",retryAfterMs=4000),1))
        assertNull(PhraseAudioCache.retryDelay(EdgeFailure("HTTP_429",retryAfterMs=6000),1))
        assertNull(PhraseAudioCache.retryDelay(EdgeFailure("HTTP_503"),3))
    }
    @Test fun pauseResetAndVoiceChangeDuringBackoffCancelOldGeneration() = runTest {
        for(action in listOf("pause","book","voice")) {
            val dir=Files.createTempDirectory("cancel-retry").toFile();var requests=0;val heard=mutableListOf<Voice>();var playing=Voice.DMITRY
            val d=SpeechDiagnostics();val cache=PhraseAudioCache(dir){_,v,_->requests++;playing=v;if(v==Voice.DMITRY)throw EdgeFailure("IO_SocketException");ByteArray(200)}
            try {
                val q=SpeechQueue(this,d,{p,v,a->cache.get(p,v,a)},{_,_,s->s();heard+=playing},{},{},{true},{true},{},StandardTestDispatcher(testScheduler))
                q.start(items.take(1),Voice.DMITRY);runCurrent();assertEquals(1,requests)
                when(action){"pause"->q.pause();"book"->q.reset();else->q.start(items.take(1),Voice.SVETLANA)}
                advanceUntilIdle();assertEquals(if(action=="voice")listOf(Voice.SVETLANA)else emptyList<Voice>(),heard)
                assertEquals(if(action=="voice")2 else 1,requests)
            }finally{dir.deleteRecursively()}
        }
    }
    @Test fun retryingPrefetchCannotInterruptCurrentAudio() = runTest {
        val dir=Files.createTempDirectory("prefetch-retry").toFile();val finish=CompletableDeferred<Unit>();var first=true
        val d=SpeechDiagnostics();val cache=PhraseAudioCache(dir){t,_,_->if(t==items[1].text)throw EdgeFailure("IO_SocketException");ByteArray(200)}
        try {
            val q=SpeechQueue(this,d,{p,v,a->cache.get(p,v,a)},{_,_,s->s();if(first){first=false;finish.await()}},{},{},{true},{true},{},StandardTestDispatcher(testScheduler))
            q.start(items.take(2),Voice.DMITRY);runCurrent();advanceTimeBy(2500);runCurrent()
            assertEquals(SpeechQueue.State.PLAYING,q.state);assertEquals(0,q.cursor)
            finish.complete(Unit);advanceUntilIdle();assertEquals(1,q.cursor);assertEquals(SpeechQueue.State.ERROR,q.state)
        }finally{dir.deleteRecursively()}
    }
    @Test fun lateCloseAndOldRetryCannotRewriteTerminalOrNewSession() {
        val d=SpeechDiagnostics();d.beginRun(1);val old=d.attempt(Voice.DMITRY,block=3)
        old.record("opened",http=101);old.record("opened",code="IO_SocketException");old.record("connect")
        old.record("opened",close=1000)
        assertEquals("error",rows(d).getJSONObject(0).getString("outcome"))
        d.beginRun(2);val current=d.attempt(Voice.SVETLANA,block=1)
        current.record("synthesized");old.retry(2).record("completed");old.record("completed",close=1000)
        assertEquals(1,rows(d).length());assertEquals(current.id,rows(d).getJSONObject(0).getString("id"))
    }
    private fun response(partial: Boolean?, marker: Byte, disconnect: (() -> Unit)? = null) = MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
        override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,null)}
        override fun onMessage(webSocket:WebSocket,text:String){if(text.startsWith("X-RequestId:")){
            if(partial!=false){val h="Path:audio\r\n".toByteArray();webSocket.send(ByteString.of(*(byteArrayOf(0,h.size.toByte())+h+ByteArray(200){marker})))}
            if(partial==null)webSocket.send("Path:turn.end\r\n\r\n") else if (disconnect != null) Thread { Thread.sleep(100); disconnect() }.start() else webSocket.close(1011,null)
        }}
    })
    @Test fun realWebsocketBeforeAndAfterPartialAudioNeverCommitsFailedBytes() = runBlocking {
        // 1011 is deliberately not retried: inspect failed cache, then perform an explicit new request.
        for(partial in listOf(false,true)){
            val server=MockWebServer();server.start();val dir=Files.createTempDirectory("ws-partial").toFile();val d=SpeechDiagnostics()
            val client=EdgeClient(server.url("/edge").toString());val cache=PhraseAudioCache(dir){t,v,a->client.synthesize(t,v,a)}
            try{
                server.enqueue(response(partial,7));server.enqueue(response(null,9))
                try{cache.get(items[0],Voice.DMITRY,d.attempt(Voice.DMITRY));fail()}catch(e:EdgeFailure){assertEquals("CLOSED_1011",e.code)}
                assertTrue(dir.listFiles()!!.isEmpty())
                val file=cache.get(items[0],Voice.DMITRY,d.attempt(Voice.DMITRY));assertTrue(file.readBytes().all{it==9.toByte()})
                assertEquals(2,server.requestCount);val failed=rows(d).getJSONObject(0)
                assertEquals(101,failed.getInt("httpStatus"));assertEquals(if(partial)200 else 0,failed.optInt("audioBytes"))
                assertEquals(partial,failed.has("firstAudioMs"))
            }finally{client.close();server.shutdown();dir.deleteRecursively()}
        }
    }
    @Test fun transportDisconnectAfter101RetriesFreshSocketAndFreshAudioBuffer() = runBlocking {
        for(partial in listOf(false,true)) {
            val sockets=java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
            val server=MockWebServer()
            server.serverSocketFactory=object:javax.net.ServerSocketFactory(){
                override fun createServerSocket():java.net.ServerSocket=object:java.net.ServerSocket(){
                    override fun accept():java.net.Socket=super.accept().also{sockets+=it}
                }
                override fun createServerSocket(p:Int)=java.net.ServerSocket(p)
                override fun createServerSocket(p:Int,b:Int)=java.net.ServerSocket(p,b)
                override fun createServerSocket(p:Int,b:Int,a:java.net.InetAddress)=java.net.ServerSocket(p,b,a)
            }
            server.start();val dir=Files.createTempDirectory("ws-retry").toFile();val d=SpeechDiagnostics()
            val client=EdgeClient(server.url("/edge").toString());val cache=PhraseAudioCache(dir){t,v,a->client.synthesize(t,v,a)}
            try {
                server.enqueue(response(partial,7) { sockets.first().close() });server.enqueue(response(null,9))
                val file=withTimeout(10000){cache.get(items[0],Voice.DMITRY,d.attempt(Voice.DMITRY,block=3))}
                assertEquals(200,file.length().toInt());assertTrue(file.readBytes().all{it==9.toByte()})
                assertEquals(2,server.requestCount)
                val first=server.takeRequest();val second=server.takeRequest()
                assertNotEquals(first.requestUrl!!.queryParameter("ConnectionId"),second.requestUrl!!.queryParameter("ConnectionId"))
                val a=rows(d).getJSONObject(0);val b=rows(d).getJSONObject(1)
                assertEquals(101,a.getInt("httpStatus"));assertEquals(if(partial)200 else 0,a.getInt("audioBytes"))
                assertEquals("error",a.getString("outcome"));assertEquals(2,b.getInt("networkAttempt"));assertEquals("success",b.getString("synthesis"))
                assertFalse(b.has("playbackMs"));assertFalse(dir.listFiles()!!.any{it.extension=="tmp"})
            }finally{client.close();server.shutdown();dir.deleteRecursively()}
        }
    }

}
