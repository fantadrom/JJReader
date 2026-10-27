package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.report.*
import com.fantadrom.jjreader.text.TextModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NoteReportTest {
    private fun source(vararg text:String)=BookText(Book("id","Unused",File("unused"),text.mapIndexed{i,s->Chapter("r$i.xhtml","Ресурс $i",s)}))
    private fun note(s:BookText,id:String,r:Int,a:Int,b:Int)=JSONObject().put("id",id).put("bookId","id").put("chapter",r).put("start",a).put("end",b).put("quote",TextModel.slice(s.book.chapters[r].text,a,b)).put("comment","Комментарий $id").put("color","blue")
    @Test fun logicalChapterCrossesResourcesAndTwoChaptersCanShareOneResource(){
        val s=source("Первая часть.\n\nКонец ресурса.","Продолжение той же главы.\n\nНовая глава.\n\nЕё продолжение.","Последний ресурс той же новой главы.")
        val split=TextModel.length(s.book.chapters[1].text.substringBefore("Новая глава."))
        val chapters=ChapterMap.build(s,listOf(TocPoint("Часть",0,0,0),TocPoint("Первая логическая",0,0,1),TocPoint("Вторая логическая",1,split,1)))
        assertEquals(2,chapters.size);assertEquals("Первая логическая",chapters[0].title);assertTrue(chapters[0].end>s.starts[1]);assertEquals(s.size,chapters[1].end)
        val builder=ReportBuilder(BookAnalysis(s,chapters));val a=note(s,"a",0,20,25);val b=note(s,"b",1,0,5)
        val report=builder.build(listOf(a,b),350);assertEquals(1,report.groups.size);assertTrue(report.groups[0].context.text.contains("Продолжение той же главы"));assertFalse(report.groups[0].context.text.contains("Новая глава"))
        val later=builder.build(listOf(note(s,"c",1,split,split+5)),350);assertFalse(later.groups[0].context.text.contains("Первая часть"));assertTrue(later.groups[0].context.text.contains("Последний ресурс"))
    }
    @Test fun ambiguityStopsChapterInheritanceAndFallbackIsResourceScoped(){
        val s=source("Один.","Два.","Три.","Четыре.")
        val map=ChapterMap.build(s,listOf(TocPoint("Известная",0,0),TocPoint("Неясная",1,null),TocPoint("Следующая",3,0)))
        assertEquals(4,map.size);assertTrue(map[1].fallback);assertTrue(map[2].fallback);assertFalse(map[3].fallback)
        val report=ReportBuilder(BookAnalysis(s,map)).build(listOf(note(s,"a",0,0,2),note(s,"b",1,0,2)),10000);assertEquals(2,report.groups.size)
        assertEquals(4,ChapterMap.build(s,emptyList()).size)
        val nested=ChapterMap.build(s,listOf(TocPoint("Родитель",0,0,0),TocPoint("Неясный уровень",1,0,1),TocPoint("Следующая глава",3,0,0)))
        assertTrue(nested.take(3).all{it.fallback});assertFalse(nested.last().fallback)
    }
    @Test fun farthestParagraphBoundariesUseSeveralParagraphsAndKeepWholeSelection(){
        val text="Начало.\n\nКороткий один.\n\nКороткий два.\n\nВыделено целиком.\n\nПосле один.\n\nПосле два.\n\nКонец."
        val s=source(text);val a=TextModel.length(text.substringBefore("Выделено"));val b=a+8
        val builder=ReportBuilder(BookAnalysis(s,ChapterMap.build(s,listOf(TocPoint("Глава",0,0)))))
        val c=builder.context(a,b,ChapterMap.build(s,listOf(TocPoint("Глава",0,0))).single(),35)
        assertEquals(text.indexOf("Короткий один."),c.start);assertTrue(c.text.contains("Короткий два."));assertTrue(c.text.contains("После два."));assertTrue(c.leadingEllipsis);assertTrue(c.trailingEllipsis)
        val full=builder.build(listOf(note(s,"one",0,a,a+16)),1000).groups.single().context;assertEquals(text,full.text);assertFalse(full.leadingEllipsis);assertFalse(full.trailingEllipsis)
        val zero=builder.build(listOf(note(s,"one",0,a,a+16)),0).groups.single().context;assertEquals(TextModel.slice(text,a,a+16),zero.text)
    }
    @Test fun longParagraphCutsWholeWordsInsideBudgetAndPreservesUnicode(){
        val text="ДлинноеСлово "+"слово ".repeat(12)+"е\u0301ж 👩‍💻 цитата "+"после ".repeat(12)+"Конец"
        val s=source(text);val a=TextModel.length(text.substringBefore("цитата"));val b=a+6;val chapter=ChapterMap.build(s,listOf(TocPoint("Глава",0,0))).single()
        val c=ReportBuilder(BookAnalysis(s,listOf(chapter))).context(a,b,chapter,15)
        assertTrue(a-c.start<=15);assertTrue(c.end-b<=15);assertTrue(c.text.contains("цитата"));assertTrue(c.text.contains("👩‍💻"));assertFalse(Character.isLowSurrogate(c.text.first()));assertFalse(c.text.startsWith("\u0301"));assertTrue(c.leadingEllipsis&&c.trailingEllipsis)
    }
    @Test fun contextOverlapAloneDoesNotMergeAndChainIsOrderIndependent(){
        val s=source("a ".repeat(150));val model=BookAnalysis(s,ChapterMap.build(s,listOf(TocPoint("Глава",0,0))));val builder=ReportBuilder(model)
        val separate=builder.build(listOf(note(s,"a",0,40,41),note(s,"b",0,66,67)),18)
        assertEquals(2,separate.groups.size);assertTrue(separate.groups[0].context.end>separate.groups[1].context.start)
        val records=listOf(note(s,"c",0,88,89),note(s,"a",0,40,41),note(s,"b",0,64,65),note(s,"d",0,64,65))
        repeat(25){seed->val report=builder.build(records.shuffled(kotlin.random.Random(seed)),25);assertEquals(1,report.groups.size);assertEquals(listOf("a","b","d","c"),report.groups.single().notes.map{it.note.id});assertEquals(listOf(1,2,3,4),report.groups.single().notes.map{it.number});assertEquals(4,report.groups.flatMap{it.notes}.map{it.note.id}.distinct().size)}
    }
    @Test fun neverMergeAcrossLogicalChaptersAndNeverDiscardUnresolvedOrCrossingQuotes(){
        val s=source("a ".repeat(100));val map=ChapterMap.build(s,listOf(TocPoint("A",0,0),TocPoint("B",0,100)))
        val records=listOf(note(s,"a",0,90,95),note(s,"b",0,104,110),note(s,"cross",0,98,104),note(s,"bad",0,20,21).put("quote","wrong"))
        val report=ReportBuilder(BookAnalysis(s,map)).build(records,500)
        val wrongLocator=ReportBuilder(BookAnalysis(s,map)).build(listOf(note(s,"mismatch",0,20,21).put("locator",JSONObject().put("href","another.xhtml"))),350)
        assertEquals(1,wrongLocator.unresolved.size)
        assertEquals(3,report.groups.size);assertEquals(1,report.unresolved.size);assertEquals("bad",report.unresolved.single().raw.getString("id"));val cross=report.groups.first{it.notes.single().note.id=="cross"};assertEquals(records[2].getString("quote"),cross.context.text)
    }
    @Test fun fragmentIndexUsesCanonicalCodePointsAndRejectsDuplicateIds(){
        val html="<h1 id='a'>Заголовок😀</h1><p id='b'>е́ж 👩‍💻 слово</p><p id='duplicate'>Один</p><p id='duplicate'>Два</p><p hidden='hidden' id='hide'>Нет</p>"
        val index=TextModel.index(html);assertEquals(TextModel.normalize(html),index.text);assertEquals(TextModel.length("Заголовок😀\n\n"),index.targets["b"]);assertFalse(index.targets.containsKey("duplicate"));assertFalse(index.targets.containsKey("hide"))
    }
}
