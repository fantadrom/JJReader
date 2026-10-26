package com.fantadrom.jjreader.engine

import com.fantadrom.jjreader.books.Book
import com.fantadrom.jjreader.text.TextModel
import com.fantadrom.jjreader.tts.Phrase
import com.fantadrom.jjreader.tts.Phrases
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/** The only source/render coordinate boundary. Persistent addresses always remain source-local. */
class RenditionAdapter(val book:Book,val unified:UnifiedDocument?=null) {
    val mode get()=if(unified==null)"legacy"else"unified"
    private val lengths=book.chapters.map{TextModel.length(it.text)}
    private val prefixes=IntArray(lengths.size+1).apply{for(i in lengths.indices)this[i+1]=this[i]+lengths[i]}
    fun absolute(chapter:Int,offset:Int):Int {require(chapter in lengths.indices && offset in 0..lengths[chapter]);return prefixes[chapter]+offset}
    fun sourceAt(absolute:Int):Pair<Int,Int>? {
        require(absolute in 0..prefixes.last())
        val index=lengths.indices.firstOrNull{lengths[it]>0 && absolute<prefixes[it+1]}
            ?: lengths.indices.lastOrNull{lengths[it]>0} ?: return null
        return index to absolute-prefixes[index]
    }
    fun nextReadable(chapter:Int)= (chapter until lengths.size).firstOrNull{lengths[it]>0} ?: (chapter downTo 0).firstOrNull{lengths[it]>0}
    fun initialLocator(data:JSONObject):Locator? = if(unified!=null)Locator.fromJSON(JSONObject().put("href",UnifiedDocument.HREF).put("type","application/xhtml+xml")) else data.optJSONObject("locator")?.let(Locator::fromJSON)
    fun sourceLocator(chapter:Int,anchor:JSONObject):Locator {
        val locations=anchor.optJSONObject("locations")?.let{JSONObject(it.toString())} ?: JSONObject().put("progression",if(lengths[chapter]==0)0.0 else anchor.getInt("start").toDouble()/lengths[chapter])
        fun original(selector:String)=selector.replace(Regex("^(html[^>]*>\\s*body[^>]*)(?:>\\s*section[^>]*>\\s*div[^>]*)(?=>|$)"),"$1")
        if(locations.has("cssSelector"))locations.put("cssSelector",original(locations.getString("cssSelector")))
        locations.optJSONObject("domRange")?.let{dom->for(key in listOf("start","end"))dom.optJSONObject(key)?.let{it.put("cssSelector",original(it.getString("cssSelector")))}}
        return checkNotNull(Locator.fromJSON(JSONObject().put("href",book.chapters[chapter].href).put("type","application/xhtml+xml")
            .put("locations",locations).put("text",anchor.optJSONObject("locatorText") ?: JSONObject().put("highlight",anchor.getString("quote")))))
    }
    fun boundary(chapter:Int):Pair<Int,Int>?=nextReadable(chapter)?.let{it to if(it<chapter)lengths[it]-1 else 0}
    suspend fun anchor(bridge:LocatorBridge,nav:EpubNavigatorFragment,chapter:Int,start:Int,end:Int):JSONObject {
        val scope=if(unified==null)"JJ"else"JJ.forSection($chapter)"
        return bridge.call(nav,"$scope.anchor($start,$end)")?.put("chapter",chapter) ?: error("ANCHOR_RANGE")
    }
    suspend fun displayLocator(bridge:LocatorBridge,nav:EpubNavigatorFragment,note:JSONObject):Locator {
        val chapter=note.getInt("chapter");require(chapter in lengths.indices)
        if(note.has("bookId"))check(note.getString("bookId")==book.id){"ANCHOR_BOOK"}
        if(note.has("textHash"))check(note.getString("textHash")==TextModel.hash(book.chapters[chapter].text.toByteArray())){"ANCHOR_HASH"}
        check(TextModel.slice(book.chapters[chapter].text,note.getInt("start"),note.getInt("end"))==note.getString("quote")){"ANCHOR_QUOTE"}
        val anchor=anchor(bridge,nav,chapter,note.getInt("start"),note.getInt("end"))
        return bridge.locator(nav.currentLocator.value,anchor)
    }
    fun phrases(chapter:Int,start:Int):List<Phrase> {
        val result=mutableListOf<Phrase>()
        for(index in chapter until book.chapters.size){
            val phrases=Phrases.split(book.chapters[index].text).filter{index!=chapter || it.end>start}
            result+=phrases.take(5-result.size).map{it.copy(chapter=index)}
            if(result.size>=5)break
        }
        return result
    }
    internal fun port(nav:EpubNavigatorFragment,bridge:LocatorBridge,go:suspend(Int)->Boolean):ReaderPort = object:ReaderPort {
        var observed=0;var requested=0
        override val continuous get()=unified!=null
        override fun chapterIndex()=if(continuous)observed else book.chapters.indexOfFirst{it.href.substringBefore('#')==nav.currentLocator.value.href.toString().substringBefore('#')}
        override fun matches(state:JSONObject,index:Int):Boolean?=if(!continuous)null else state.optString("url").substringBefore('#').endsWith("/"+UnifiedDocument.HREF) && state.optInt("sourceChapter",-1)==index && state.optString("generation")==unified!!.generation
        override suspend fun context(index:Int):JSONObject? {requested=index;return call(if(continuous)"JJ.forSection($index).context()"else"JJ.context()")}
        override suspend fun text()=if(continuous)bridge.call(nav,"({text:JJ.forSection($requested).text})")?.getString("text")else bridge.text(nav)
        override suspend fun goResource(index:Int):Boolean {requested=index;return if(continuous)true else go(index)}
        override suspend fun go(locator:Locator){if(!continuous)nav.go(locator,animated=false)}
        override fun locator(anchor:JSONObject)=if(continuous)sourceLocator(requested,anchor)else bridge.locator(nav.currentLocator.value,anchor)
        override suspend fun call(expression:String):JSONObject? {
            val root=expression=="JJ.viewport()" || expression.startsWith("JJ.takeSelection")
            val translated=if(continuous && !root && !expression.startsWith("JJ.forSection"))expression.replace("JJ.","JJ.forSection($requested).")else expression
            val result=bridge.call(nav,translated)
            if(continuous && result!=null){
                if(root && result.has("sourceChapter"))observed=result.getInt("sourceChapter")
                if(expression=="JJ.restoreStatus()" && result.optString("phase")=="confirmed")observed=requested
            }
            return result
        }
    }
}
