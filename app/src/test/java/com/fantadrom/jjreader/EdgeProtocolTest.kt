package com.fantadrom.jjreader

import com.fantadrom.jjreader.tts.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class EdgeProtocolTest {
    private fun frame(path:String, bytes:Int)= "Path:$path\r\nContent-Type:audio/mpeg\r\n".toByteArray().let{h->ByteString.of(*(byteArrayOf(0,h.size.toByte())+h+ByteArray(bytes){9}))}
    @Test fun fullWireNamesAndUnicodeXmlAreExact() {
        for((voice,name) in listOf(Voice.DMITRY to "DmitryNeural",Voice.SVETLANA to "SvetlanaNeural")) {
            assertEquals("Microsoft Server Speech Text to Speech Voice (ru-RU, $name)",voice.wireName)
            assertEquals("ru-RU-$name",voice.wire)
            val ssml=EdgeProtocol.ssml("Ёж & <текст> \"да\" 'нет' 😀\u0000\u000b\uFFFF\uD800",voice)
            val f=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true}
            val doc=f.newDocumentBuilder().parse(ByteArrayInputStream(ssml.toByteArray()))
            assertEquals(voice.wireName,doc.getElementsByTagNameNS("*","voice").item(0).attributes.getNamedItem("name").nodeValue)
            assertEquals("ru-RU",doc.documentElement.getAttribute("xml:lang"))
            assertEquals("Ёж & <текст> \"да\" 'нет' 😀    ",doc.getElementsByTagNameNS("*","prosody").item(0).textContent)
        }
    }
    @Test fun protocolMessagesCountsSafeErrorAndSendAcceptanceAreRecorded() = runBlocking {
        for(mode in listOf("serviceOnly","binaryOther","error","success")) {
            val server=MockWebServer();server.start();val sent=mutableListOf<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onClosing(w:WebSocket,code:Int,reason:String){w.close(code,null)}
                override fun onMessage(w:WebSocket,text:String){synchronized(sent){sent+=text};if(text.startsWith("X-RequestId:")){
                    w.send("Path:turn.start\r\n\r\n")
                    w.send("Path:response\r\n\r\n"+if(mode=="error")"{\"error\":{\"code\":\"InvalidArgument\",\"message\":\"PRIVATE SSML SECRET\"}}"else"{\"context\":{\"synthesis\":{\"status\":\"Success\"}}}")
                    if(mode=="binaryOther")w.send(frame("private-unknown-path",200))
                    if(mode=="success")w.send(frame("audio",200))
                    w.send("Path:turn.end\r\n\r\n")
                }}
            }))
            val client=EdgeClient(server.url("/edge").toString());val d=SpeechDiagnostics();val a=d.attempt(Voice.DMITRY)
            try {
                try{val bytes=client.synthesize("PRIVATE & текст",Voice.DMITRY,a);assertEquals("success",mode);assertEquals(200,bytes.size)}
                catch(e:EdgeFailure){assertEquals(if(mode=="error")"SERVER_PROTOCOL_ERROR"else"NO_AUDIO",e.code)}
                val row=JSONObject(d.text()).getJSONArray("attempts").getJSONObject(0)
                assertTrue(row.getBoolean("configSendAccepted"));assertTrue(row.getBoolean("ssmlSendAccepted"));assertEquals("full",row.getString("wireNameKind"))
                assertEquals(if(mode=="binaryOther"||mode=="success")1 else 0,row.getInt("binaryMessages"))
                assertEquals(if(mode=="error")2 else 3,row.getInt("textMessages"));assertEquals(mode=="error",row.getBoolean("protocolError"))
                if(mode=="error")assertEquals("invalid_request",row.getString("protocolCategory"))
                assertEquals(if(mode=="success")200 else 0,row.getInt("audioBytes"))
                assertEquals(2,sent.size);assertTrue(sent[0].contains("Path:speech.config\r\n\r\n"));assertTrue(sent[0].contains("audio-24khz-48kbitrate-mono-mp3"))
                assertTrue(sent[1].contains("X-Timestamp:"));assertTrue(sent[1].contains(")Z\r\nPath:ssml"));assertTrue(sent[1].contains(Voice.DMITRY.wireName));assertTrue(sent[1].contains("PRIVATE &amp; текст"))
                listOf("PRIVATE","SECRET","SSML","private-unknown-path","Cookie","TrustedClientToken").forEach{assertFalse(d.text().contains(it))}
            }finally{client.close();server.shutdown()}
        }
    }
    @Test fun unknownServerErrorIsCategorizedWithoutExportingItsCode() {
        assertEquals("server_error_other",EdgeProtocol.error(emptyMap(),"{\"error\":{\"code\":\"PRIVATE\",\"message\":\"secret\"}}"))
        assertNull(EdgeProtocol.error(emptyMap(),"{\"private\":\"secret\"}"))
        assertEquals("other",EdgeProtocol.path(mapOf("path" to "private")))
    }
    @Test fun explicitVoiceProbeIsSequentialUncachedAndKeepsBothResults() = runBlocking {
        val dir=Files.createTempDirectory("probe").toFile();val d=SpeechDiagnostics();val fetched=mutableListOf<Voice>();val played=mutableListOf<Voice>()
        try {
            VoiceProbe(d,{text,v,a->assertEquals(VoiceProbe.TEXT,text);fetched+=v;a.record("opened",http=101)
                if(v==Voice.DMITRY)throw EdgeFailure("NO_AUDIO","opened")
                a.record("synthesized");dir.resolve("probe.mp3").apply{writeBytes(ByteArray(200))}
            },{_,a->a.record("playback");played+=Voice.SVETLANA},{}).run(-1)
            assertEquals(Voice.entries,fetched);assertEquals(listOf(Voice.SVETLANA),played);assertTrue(dir.listFiles()!!.isEmpty())
            val report=JSONObject(d.text());assertEquals("voiceCheck",report.getString("purpose"));val rows=report.getJSONArray("attempts")
            assertEquals(2,rows.length());repeat(2){assertEquals(1,rows.getJSONObject(it).getInt("maxAttempts"));assertEquals("network",rows.getJSONObject(it).getString("source"))}
            assertEquals("error",rows.getJSONObject(0).getString("outcome"));assertEquals("completed",rows.getJSONObject(1).getString("playback"))
        }finally{dir.deleteRecursively()}
    }
    @Test fun cancellingProbeStopsRequestAndSecondVoiceWithoutAutoResume() = runBlocking {
        val d=SpeechDiagnostics();val opened=CompletableDeferred<Unit>();var calls=0;var played=false
        val job=launch{VoiceProbe(d,{_,_,_->calls++;opened.complete(Unit);awaitCancellation()},{_,_->played=true},{}).run(-1)}
        opened.await();job.cancelAndJoin();assertEquals(1,calls);assertFalse(played)
        assertEquals("cancelled",JSONObject(d.text()).getJSONArray("attempts").getJSONObject(0).getString("outcome"))
    }
}
