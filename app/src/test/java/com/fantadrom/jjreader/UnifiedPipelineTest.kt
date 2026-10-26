package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class UnifiedPipelineTest {
    @Test fun sourceToDerivedToRealReadiumInjectionProofAndCacheRecovery()=runBlocking {
        val source=File("src/test/resources/unified/small.epub").absoluteFile
        val sourcePub=ReadiumEngine(RuntimeEnvironment.getApplication()).open(source)
        val chapters=try{ReadiumEngine(RuntimeEnvironment.getApplication()).chapters(sourcePub)}finally{sourcePub.close()}
        val book=Book(UUID.randomUUID().toString(),"Synthetic",source,chapters)
        val root=File("build/unified-cache").absoluteFile;val before=UnifiedDocument.hashFile(source)
        val document=UnifiedDocument.prepare(book,root,chapters.map{it.title to it.href})
        assertFalse(document.cacheHit);assertEquals(before,UnifiedDocument.hashFile(source))
        val pub=ReadiumEngine(RuntimeEnvironment.getApplication()).open(document.file)
        try{
            assertEquals(1,pub.readingOrder.size)
            val html=pub.get(pub.readingOrder.single())!!.read().getOrNull()!!.toString(Charsets.UTF_8)
            val xml=XhtmlPipelineTest.strictXml(html);assertEquals(chapters.size,xml.getElementsByTagName("section").length)
            val rendered=ReadiumInjection.apply(html,"application/xhtml+xml");XhtmlPipelineTest.strictXml(rendered)
            val out=File("build/unified-regression").apply{mkdirs()}
            File(out,"unified.injected.xhtml").writeText(rendered);File(out,"map.json").writeText(document.map.toString(2))
            File(out,"model.json").writeText(JSONObject().put("chapters",org.json.JSONArray(chapters.map{JSONObject().put("href",it.href).put("text",it.text)})).toString())
            document.file.copyTo(File(out,"rendition.epub"),overwrite=true)
        }finally{pub.close()}
        val reused=UnifiedDocument.prepare(book,root,chapters.map{it.title to it.href});assertTrue(reused.cacheHit);assertEquals(document.generation,reused.generation)
        File(document.file.parentFile,"map.json").appendText("broken")
        val rebuilt=UnifiedDocument.prepare(book,root,chapters.map{it.title to it.href});assertFalse(rebuilt.cacheHit);assertNotEquals(document.generation,rebuilt.generation)
        assertEquals(before,UnifiedDocument.hashFile(source));root.resolve(book.id).deleteRecursively();Unit
    }
    @Test fun interruptedGenerationPublishesNeitherPartialHtmlNorMapAndKeepsSourceAndReader()=runBlocking {
        val root=java.nio.file.Files.createTempDirectory("unified-interrupt").toFile()
        try {
            val source=File(root,"source.epub")
            val html="<html xmlns='http://www.w3.org/1999/xhtml'><head></head><body><p>Синтетический текст.</p></body></html>"
            val chapters=(0 until 300).map{Chapter("s$it.xhtml","unused",TextModel.normalize(html))}
            java.util.zip.ZipOutputStream(source.outputStream()).use{zip->
                fun entry(name:String,text:String){zip.putNextEntry(java.util.zip.ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()}
                entry("META-INF/container.xml","<container/>");entry("mimetype","application/epub+zip")
                chapters.forEach{entry(it.href,html)}
            }
            val reader=File(root,"reader.json").apply{writeText("{\"schema\":1,\"notes\":[],\"fontSize\":1.45}")}
            val before=UnifiedDocument.hashFile(source);val oldReader=reader.readText()
            val book=Book(UUID.randomUUID().toString(),"Synthetic",source,chapters);val cache=File(root,"cache")
            val job=async(Dispatchers.Default){UnifiedDocument.prepare(book,cache,emptyList())}
            withTimeout(30000){while(cache.resolve(book.id).listFiles()?.none{it.name.startsWith(".stage-")}!=false){check(!job.isCompleted);delay(1)}}
            job.cancelAndJoin()
            assertEquals(before,UnifiedDocument.hashFile(source));assertEquals(oldReader,reader.readText())
            assertTrue(cache.resolve(book.id).listFiles().orEmpty().isEmpty())
        }finally{root.deleteRecursively()};Unit
    }

}
