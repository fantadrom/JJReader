@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader.report

import android.content.Context
import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.text.TextModel
import org.json.*
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.Link
import java.io.File
import java.net.URI

/** Durable chapter map beside the already durable book.json text. EPUB is unnecessary for P3 reports. */
class BookAnalysis(val source:BookText,val chapters:List<LogicalChapter>) {
    fun validate(note:JSONObject){
        val r=note.getInt("chapter");val a=note.getInt("start");val b=note.getInt("end")
        require(r in source.starts.indices&&a>=0&&b>a&&b<=source.lengths[r])
        require(note.optString("bookId",source.book.id)==source.book.id)
        require(source.slice(source.absolute(r,a),source.absolute(r,b))==note.getString("quote"))
        if(note.has("textHash"))require(note.getString("textHash")==source.hashes[r])
        note.optJSONObject("locator")?.optString("href")?.takeIf{it.isNotEmpty()}?.let{href->require(URI(href.replace(" ","%20")).path?.trimStart('/')==URI(source.book.chapters[r].href.replace(" ","%20")).path?.trimStart('/'))}
    }
    fun chapterAt(offset:Int)=chapters.firstOrNull{offset>=it.start&&offset<it.end} ?: chapters.lastOrNull()
    companion object {
        private fun file(context:Context,id:String)=File(context.filesDir,"books-v1/$id/analysis-v1.json")
        private fun hashes(book:Book)=JSONArray(book.chapters.map{TextModel.hash(it.text.toByteArray())}).toString()
        fun cached(context:Context,book:Book):BookAnalysis?=runCatching {
            val data=BookStore.readAtomic(file(context,book.id));check(data.getInt("schema")==1&&data.optString("algorithm")=="toc-cp-v1"&&data.getString("sourceHashes")==hashes(book))
            val source=BookText(book);val a=data.getJSONArray("logicalChapters")
            val chapters=(0 until a.length()).map{val c=a.getJSONObject(it);LogicalChapter(c.getString("id"),c.getString("title"),c.getInt("start"),c.getInt("end"),c.getBoolean("fallback"))}
            check(chapters.isEmpty()&&source.size==0 || chapters.first().start==0&&chapters.last().end==source.size&&chapters.all{it.end>it.start}&&chapters.zipWithNext().all{it.first.end==it.second.start})
            BookAnalysis(source,chapters)
        }.getOrNull()
        fun fallback(book:Book):BookAnalysis{val source=BookText(book);return BookAnalysis(source,ChapterMap.build(source,emptyList()))}
        suspend fun prepare(context:Context,book:Book,pub:Publication):BookAnalysis {
            cached(context,book)?.let{return it}
            val source=BookText(book);val targets=mutableMapOf<Int,Map<String,Int>>()
            val points=mutableListOf<TocPoint>()
            suspend fun visit(links:List<Link>,depth:Int){for(link in links){
                val url=link.url().toString();val path=URI(url.replace(" ","%20")).path;val index=book.chapters.indexOfFirst{URI(it.href.replace(" ","%20")).path==path}
                if(index>=0){
                    val fragment=URI(url.replace(" ","%20")).fragment
                    val offset=if(fragment.isNullOrEmpty())0 else {
                        if(index !in targets){val html=pub.get(pub.readingOrder[index])?.read()?.getOrNull()?.toString(Charsets.UTF_8);val indexed=html?.let(TextModel::index);check(indexed?.text==book.chapters[index].text){"CONTEXT_SOURCE_MISMATCH"};targets[index]=indexed.targets}
                        targets[index]?.get(fragment)
                    }
                    if(!link.title.isNullOrBlank()||link.children.isEmpty())points+=TocPoint(link.title ?: "",index,offset,depth)
                }
                visit(link.children,depth+1)
            }}
            visit(pub.tableOfContents,0)
            val chapters=ChapterMap.build(source,points)
            val data=JSONObject().put("schema",1).put("algorithm","toc-cp-v1").put("sourceHashes",hashes(book)).put("logicalChapters",JSONArray(chapters.map{JSONObject().put("id",it.id).put("title",it.title).put("start",it.start).put("end",it.end).put("fallback",it.fallback)}))
            BookStore.writeAtomic(file(context,book.id),data)
            return BookAnalysis(source,chapters)
        }
    }
}
