package com.fantadrom.jjreader.report

import com.fantadrom.jjreader.books.Book
import com.fantadrom.jjreader.text.TextModel
import java.text.BreakIterator
import java.util.Locale

/** Canonical source-local v1 remains unchanged. Two LF join resources in the report corpus only. */
class BookText(val book:Book) {
    val starts=IntArray(book.chapters.size)
    val lengths=book.chapters.map{TextModel.length(it.text)}
    val hashes=book.chapters.map{TextModel.hash(it.text.toByteArray())}
    val text:String
    val cp:IntArray
    val utf16:IntArray
    val paragraphStarts:IntArray
    val paragraphEnds:IntArray
    val wordStarts:IntArray
    val wordEnds:IntArray
    init {
        val out=StringBuilder();var offset=0
        book.chapters.forEachIndexed{i,r->if(i>0){out.append("\n\n");offset+=2};starts[i]=offset;out.append(r.text);offset+=lengths[i]}
        text=out.toString();cp=text.codePoints().toArray();utf16=IntArray(cp.size+1)
        for(i in cp.indices)utf16[i+1]=utf16[i]+Character.charCount(cp[i])
        val ps=mutableListOf(0);val pe=mutableListOf<Int>()
        for(i in 0 until cp.size-1)if(cp[i]==10&&cp[i+1]==10){pe+=i;ps+=i+2}
        pe+=cp.size;paragraphStarts=ps.distinct().toIntArray();paragraphEnds=pe.distinct().toIntArray()
        val ws=mutableListOf<Int>();val we=mutableListOf<Int>();val iterator=BreakIterator.getWordInstance(Locale.forLanguageTag("ru"));iterator.setText(text)
        var a=iterator.first();var b=iterator.next()
        while(b!=BreakIterator.DONE){if(text.substring(a,b).codePoints().anyMatch{Character.isLetterOrDigit(it)}){ws+=cpAt(a);we+=cpAt(b)};a=b;b=iterator.next()}
        wordStarts=ws.toIntArray();wordEnds=we.toIntArray()
    }
    val size get()=cp.size
    fun cpAt(offset:Int)=utf16.binarySearch(offset).let{require(it>=0);it}
    fun absolute(resource:Int,offset:Int):Int{require(resource in starts.indices&&offset in 0..lengths[resource]);return starts[resource]+offset}
    fun slice(start:Int,end:Int)=text.substring(utf16[start],utf16[end])
}

data class LogicalChapter(val id:String,val title:String,val start:Int,val end:Int,val fallback:Boolean)
data class TocPoint(val title:String,val resource:Int,val offset:Int?,val depth:Int=0)

/** Deterministic TOC boundaries, independent of navigator documents. Ambiguity stops inheritance. */
object ChapterMap {
    fun build(source:BookText,points:List<TocPoint>):List<LogicalChapter> {
        val bad=points.filter{it.offset==null||it.title.isBlank()}.map{it.resource}.toMutableSet()
        val candidates=points.filter{it.resource in source.starts.indices&&it.offset!=null&&it.title.isNotBlank()}
            .groupBy{source.absolute(it.resource,it.offset!!)}
        val chosen=linkedMapOf<Int,TocPoint>()
        candidates.toSortedMap().forEach{(pos,rows)->val depth=rows.maxOf{it.depth};val deepest=rows.filter{it.depth==depth};if(deepest.map{it.title}.distinct().size==1)chosen[pos]=deepest.first()else bad+=rows.map{it.resource}}
        // Distinct nested targets do not reveal whether a level means part, chapter or subsection.
        // Coincident parent/child targets were already collapsed; use resources on ambiguous spans.
        val ordered=chosen.entries.toList()
        for(i in ordered.indices){
            val parent=ordered[i];var j=i+1
            while(j<ordered.size&&ordered[j].value.depth>parent.value.depth)j++
            if(j>i+1){val end=ordered.getOrNull(j)?.key ?: source.size
                for(r in source.starts.indices){val a=source.starts[r];val b=source.starts.getOrNull(r+1) ?: source.size;if(a<end&&b>parent.key)bad+=r}
            }
        }
        val result=mutableListOf<LogicalChapter>();var active:Pair<Int,TocPoint>?=null
        for(i in source.starts.indices){
            val start=source.starts[i];val end=source.starts.getOrNull(i+1) ?: source.size
            if(i in bad)active=null
            val boundaries=if(i in bad)emptyList()else chosen.entries.filter{it.value.resource==i}.map{it.key}
            val cuts=(listOf(start)+boundaries+end).distinct().sorted()
            for(j in 0 until cuts.lastIndex){val a=cuts[j];val b=cuts[j+1];if(a>=b)continue
                chosen[a]?.takeIf{it.resource==i&&i !in bad}?.let{active=a to it}
                val key=active?.let{"toc-${it.first}"} ?: "resource-$i"
                val title=active?.second?.title ?: "Ресурс: ${source.book.chapters[i].title}"
                val item=LogicalChapter(key,title,a,b,active==null)
                val previous=result.lastOrNull()
                if(previous?.id==key&&previous.end==a)result[result.lastIndex]=previous.copy(end=b)else result+=item
            }
        }
        return result
    }
}
