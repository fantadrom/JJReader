package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SectionRenderingTest {
    @Test fun actualReadiumSourceDocumentsAndComparableUnifiedOutput()=runBlocking {
        val engine=ReadiumEngine(RuntimeEnvironment.getApplication())
        val fixtures=mutableListOf("small" to File("src/test/resources/sections/p08-sections.epub"))
        if(System.getProperty("jj.sectionBenchmark")=="true")fixtures+="3m" to File("../work/unified-fixtures/3m.epub")
        for((label,file) in fixtures){
            val out=File("build/section-regression/$label").apply{mkdirs()};val source=engine.open(file.absoluteFile)
            try {
                val chapters=engine.chapters(source);val book=Book(UUID.randomUUID().toString(),"Synthetic",file.absoluteFile,chapters)
                val rows=JSONArray()
                for(i in 0 until minOf(12,chapters.size)) {
                    val html=source.get(source.readingOrder[i])!!.read().getOrNull()!!.toString(Charsets.UTF_8)
                    val injected=ReadiumInjection.apply(html,"application/xhtml+xml");XhtmlPipelineTest.strictXml(injected)
                    File(out,"section-$i.xhtml").writeText(injected)
                    rows.put(JSONObject().put("href",chapters[i].href).put("length",TextModel.length(chapters[i].text)).put("text",chapters[i].text))
                }
                val began=System.nanoTime();val derived=UnifiedDocument.prepare(book,File("build/section-regression-cache").absoluteFile,source.tableOfContents.map{(it.title ?: "Раздел") to it.url().toString()})
                val prep=(System.nanoTime()-began)/1_000_000
                val pub=engine.open(derived.file)
                try{File(out,"unified.xhtml").writeText(ReadiumInjection.apply(pub.get(pub.readingOrder.single())!!.read().getOrNull()!!.toString(Charsets.UTF_8),"application/xhtml+xml"))}finally{pub.close()}
                File(out,"summary.json").writeText(JSONObject().put("sections",rows).put("total",book.total).put("sectionCount",chapters.size).put("maxChapterCP",chapters.maxOf{TextModel.length(it.text)}).put("prepareMs",prep).toString())
                file.copyTo(File(out,"source.epub"),overwrite=true);derived.file.copyTo(File(out,"derived.epub"),overwrite=true)
                assertEquals(chapters.size,derived.map.getJSONArray("sections").length())
                File("build/section-regression-cache/${book.id}").deleteRecursively()
            }finally{source.close()}
        };Unit
    }
}
