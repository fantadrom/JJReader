@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.report.*
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class P2AnalysisTest {
    @Test fun actualReadiumFragmentsAndDurableTextKeepLogicalContextAfterEpubLoss()=runBlocking {
        val context=RuntimeEnvironment.getApplication();val store=BookStore(context);val engine=ReadiumEngine(context)
        val file=File("src/test/resources/notes/p2-logical.epub").absoluteFile
        val pub=engine.open(file);val chapters=engine.chapters(pub);val book=store.commit(store.stage(file.inputStream()),"Синтетическая книга",chapters)
        try{
            val analysis=BookAnalysis.prepare(context,book,pub)
            assertEquals(listOf("Логическая Альфа","Логическая Бета"),analysis.chapters.map{it.title});assertFalse(analysis.chapters.any{it.fallback})
            val split=analysis.source.starts[1];assertTrue(analysis.chapters.first().end>split);assertTrue(analysis.chapters.last().start<analysis.source.starts[2])
            val a=TextModel.length(chapters[0].text.substringBeforeLast("граница"));val n=JSONObject().put("id","old").put("chapter",0).put("start",a).put("end",a+7).put("quote","граница").put("comment","Сохранённый комментарий").put("bookId",book.id)
            val report=ReportBuilder(analysis).build(listOf(n),350);val group=report.groups.single();assertTrue(group.context.text.contains("Продолжение Альфы"));assertFalse(group.context.text.contains("Логическая Бета"))
            val out=File("build/p2-regression").apply{mkdirs()}
            for(i in chapters.indices){val html=pub.get(pub.readingOrder[i])!!.read().getOrNull()!!.toString(Charsets.UTF_8);val injected=ReadiumInjection.apply(html,"application/xhtml+xml");XhtmlPipelineTest.strictXml(injected);File(out,"r$i.xhtml").writeText(injected)}
            File(out,"summary.json").writeText(JSONObject().put("chapters",JSONArray(analysis.chapters.map{JSONObject().put("title",it.title).put("start",it.start).put("end",it.end)})).put("source",JSONArray(chapters.map{it.text})).toString())
            val templates=ReaderDecorations.templates()
            val locator=checkNotNull(org.readium.r2.shared.publication.Locator.fromJSON(JSONObject().put("href","r0.xhtml").put("type","application/xhtml+xml")))
            val highlights=JSONArray(com.fantadrom.jjreader.notes.NoteColor.entries.mapIndexed{i,color->val d=org.readium.r2.navigator.Decoration("color-$i",locator,NoteHighlight(color.rgb,i+1));d.toJSON().put("element",templates[NoteHighlight::class]!!.element(d))})
            val overlap2=JSONArray(com.fantadrom.jjreader.notes.NoteColor.entries.take(2).mapIndexed{i,color->val d=org.readium.r2.navigator.Decoration("color-$i",locator,NoteHighlight(color.rgb,i+1,alpha=.38f/2));d.toJSON().put("element",templates[NoteHighlight::class]!!.element(d))})
            val overlap5=JSONArray(com.fantadrom.jjreader.notes.NoteColor.entries.mapIndexed{i,color->val d=org.readium.r2.navigator.Decoration("color-$i",locator,NoteHighlight(color.rgb,i+1,alpha=.38f/5));d.toJSON().put("element",templates[NoteHighlight::class]!!.element(d))})
            val mark=org.readium.r2.navigator.Decoration("comment-icon",locator,MarginMark())
            val plain=org.readium.r2.navigator.Decoration("text-note",locator,NoteHighlight(0xFF888888.toInt(),6,true))
            File(out,"decorations.json").writeText(JSONObject().put("templates",templates.toJSON()).put("highlights",highlights).put("overlap2",overlap2).put("overlap5",overlap5).put("mark",mark.toJSON().put("element",templates[MarginMark::class]!!.element(mark))).put("plain",plain.toJSON().put("element",templates[NoteHighlight::class]!!.element(plain))).toString())
            assertTrue(book.file.delete());val snapshot=store.snapshot(book.id);val cached=BookAnalysis.cached(context,snapshot)!!
            val offline=ReportBuilder(cached).build(listOf(n),350);assertEquals(group.context,offline.groups.single().context);assertEquals("Сохранённый комментарий",offline.notes.single().comment)
        }finally{pub.close();store.delete(book.id)}
    }
}
