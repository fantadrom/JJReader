package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.Book
import com.fantadrom.jjreader.engine.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.management.ManagementFactory
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class UnifiedBenchmarkTest {
    @Test fun reproducibleSyntheticSizes()=runBlocking {
        assumeTrue(System.getProperty("jj.benchmark")=="true")
        val results=JSONArray();val engine=ReadiumEngine(RuntimeEnvironment.getApplication())
        for(label in listOf("small","1m","3m")) {
            val source=File("../work/unified-fixtures/$label.epub").absoluteFile
            val sourcePub=engine.open(source)
            val book=try{Book(UUID.randomUUID().toString(),"Synthetic",source,engine.chapters(sourcePub))}finally{sourcePub.close()}
            val destination=File("build/unified-benchmark/$label").absoluteFile.apply{mkdirs()}
            val cache=File("build/unified-benchmark-cache").absoluteFile
            val metrics=JSONArray();var original:UnifiedDocument?=null
            repeat(3){
                cache.resolve(book.id).deleteRecursively()
                val d=UnifiedDocument.prepare(book,cache,book.chapters.map{it.title to it.href});metrics.put(JSONObject().put("cacheHit",false).put("prepareMs",d.prepareMs));original=d
            }
            repeat(3){val d=UnifiedDocument.prepare(book,cache,book.chapters.map{it.title to it.href});metrics.put(JSONObject().put("cacheHit",true).put("prepareMs",d.prepareMs))}
            val d=checkNotNull(original);val pub=engine.open(d.file)
            val content=try{checkNotNull(pub.get(pub.readingOrder.single())!!.read().getOrNull()).toString(Charsets.UTF_8)}finally{pub.close()}
            val injected=ReadiumInjection.apply(content,"application/xhtml+xml")
            File(destination,"unified.injected.xhtml").writeText(injected);File(destination,"map.json").writeText(d.map.toString())
            File(destination,"model-summary.json").writeText(JSONObject().put("lengths",JSONArray(book.chapters.map{com.fantadrom.jjreader.text.TextModel.length(it.text)})).toString())
            d.file.copyTo(File(destination,"rendition.epub"),overwrite=true)
            val row=JSONObject().put("fixture",label).put("codePoints",book.total).put("sections",book.chapters.size)
                .put("preparation",metrics).put("epubBytes",d.file.length()).put("xhtmlBytes",injected.toByteArray().size)
                .put("jvmHeapUsedBytes",ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
                .put("jvmHeapPeakPoolsBytes",ManagementFactory.getMemoryPoolMXBeans().filter{it.type==java.lang.management.MemoryType.HEAP}.sumOf{it.peakUsage.used})
            results.put(row);cache.resolve(book.id).deleteRecursively()
            assertEquals(1,pub.readingOrder.size)
        }
        File("build/unified-benchmark/results.json").writeText(JSONObject().put("runtime","Linux JDK25 Robolectric API35; not Android").put("runs",results).toString(2));Unit
    }
}
