package com.fantadrom.jjreader

import com.fantadrom.jjreader.engine.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.*
import com.fantadrom.jjreader.books.*
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OpeningAttemptTest {
    @Test fun failureBeforeSessionPersistsRedactedAndLateCancellationCannotOverwriteRetry() {
        val file=File.createTempFile("opening","json").apply{delete()}
        try {
            val trace=OpeningAttempt(file);val id=trace.begin("0.1.8-p0",9,true,null)
            trace.stage(id,OpeningAttempt.Stage.XML_VALIDATE);trace.provider(id,"test.Provider")
            trace.failed(id,IllegalStateException("PRIVATE /book/path",java.io.IOException("PRIVATE QUOTE")))
            val row=OpeningAttempt(file).json().getJSONObject("latest")
            assertEquals("failed",row.getString("outcome"));assertEquals("XML_VALIDATE",row.getString("stage"));assertEquals("XML_INVALID",row.getString("errorCode"));assertEquals("unified",row.getString("requestedMode"));assertTrue(row.isNull("generation"));assertTrue(row.isNull("activeMode"));assertTrue(row.getLong("elapsedMs")>=0)
            assertFalse(trace.json().toString().contains("PRIVATE"));assertEquals(2,row.getJSONArray("exceptionClasses").length())
            val legacy=trace.begin("0.1.8-p0",9,false,null);trace.success(legacy,"legacy")
            assertEquals(id,trace.json().getJSONObject("lastUnifiedFailure").getString("attemptId"))
            val retry=trace.begin("0.1.8-p0",9,true,"legacy")
            trace.failed(id,CancellationException());trace.success(retry,"unified")
            assertEquals("success",trace.json().getJSONObject("latest").getString("outcome"))
            val cancelled=trace.begin("0.1.8-p0",9,true,"unified");trace.failed(cancelled,CancellationException())
            assertEquals("cancelled",trace.json().getJSONObject("latest").getString("outcome"))
        }finally{file.delete()}
    }
    @Test fun realPreparationFailureThenLegacyAndUnifiedRetryKeepSourceAndOldReader()=runBlocking {
        val root=java.nio.file.Files.createTempDirectory("open-regression").toFile()
        try {
            val source=File("src/test/resources/unified/small.epub").absoluteFile;val before=source.readBytes()
            val engine=ReadiumEngine(RuntimeEnvironment.getApplication());val pub=engine.open(source)
            val book=try{Book(UUID.randomUUID().toString(),"Synthetic",source,engine.chapters(pub))}finally{pub.close()}
            val context=RuntimeEnvironment.getApplication()
            val reader=File(context.filesDir,"books-v1/${book.id}/reader.json");reader.parentFile!!.mkdirs()
            val store=com.fantadrom.jjreader.notes.NoteStore(context,book.id)
            val anchors=listOf(0,1).map{chapter->
                val quote=com.fantadrom.jjreader.text.TextModel.slice(book.chapters[chapter].text,0,5)
                org.json.JSONObject().put("id","old-$chapter").put("bookId",book.id).put("chapter",chapter).put("start",0).put("end",5)
                    .put("quote",quote).put("textHash",com.fantadrom.jjreader.text.TextModel.hash(book.chapters[chapter].text.toByteArray()))
                    .put("comment","kept-$chapter").put("locator",org.json.JSONObject().put("href",book.chapters[chapter].href).put("type","application/xhtml+xml"))
            }
            store.data.put("notes",org.json.JSONArray(anchors)).put("positionV2",anchors[1]).put("fontSize",1.45);store.save()
            val bytes=reader.readBytes()
            val trace=OpeningAttempt(File(root,"opening.json"));val id=trace.begin("test",9,true,null)
            try{UnifiedDocument.prepare(book,File(root,"cache"),emptyList(),{stage->trace.stage(id,stage);if(stage==OpeningAttempt.Stage.XML_VALIDATE)throw java.io.IOException("PRIVATE")});fail("Injected failure")}catch(e:java.io.IOException){trace.failed(id,e)}
            assertEquals("XML_VALIDATE",trace.json().getJSONObject("latest").getString("stage"))
            assertTrue(File(root,"cache/${book.id}").listFiles().orEmpty().isEmpty())
            val legacy=trace.begin("test",9,false,null);engine.open(source).close();trace.success(legacy,"legacy")
            val retry=trace.begin("test",9,true,"legacy");val derived=UnifiedDocument.prepare(book,File(root,"cache"),emptyList());engine.open(derived.file).let{try{assertEquals(1,it.readingOrder.size)}finally{it.close()}};trace.success(retry,"unified")
            assertArrayEquals(before,source.readBytes());assertArrayEquals(bytes,reader.readBytes());val reloaded=com.fantadrom.jjreader.notes.NoteStore(context,book.id);assertEquals(2,reloaded.notes.size);assertEquals(1,reloaded.data.getJSONObject("positionV2").getInt("chapter"));reader.parentFile!!.deleteRecursively();Unit
        }finally{root.deleteRecursively()}
    }
}
