package com.fantadrom.jjreader.report

import com.fantadrom.jjreader.notes.NoteColor
import com.fantadrom.jjreader.text.TextModel
import org.json.JSONObject

/** Common immutable input to P2 views and the future P3 renderers. No renderer computes context. */
data class ReportNote(val id:String,val resource:Int,val localStart:Int,val localEnd:Int,val start:Int,val end:Int,
                      val quote:String,val comment:String,val color:NoteColor?,val chapter:LogicalChapter?,val multiChapter:Boolean,val raw:JSONObject)
data class ContextSpan(val start:Int,val end:Int,val text:String,val leadingEllipsis:Boolean,val trailingEllipsis:Boolean)
data class NumberedNote(val number:Int,val note:ReportNote)
data class ReportGroup(val chapterTitle:String,val context:ContextSpan,val notes:List<NumberedNote>)
data class UnresolvedNote(val raw:JSONObject,val reason:String)
data class NoteReport(val groups:List<ReportGroup>,val notes:List<ReportNote>,val unresolved:List<UnresolvedNote>)

class ReportBuilder(private val analysis:BookAnalysis) {
    private val source=analysis.source
    private fun lower(values:IntArray,x:Int):Int{var a=0;var b=values.size;while(a<b){val m=(a+b) ushr 1;if(values[m]<x)a=m+1 else b=m};return a}
    fun context(start:Int,end:Int,chapter:LogicalChapter,n:Int):ContextSpan {
        require(n>=0&&start>=chapter.start&&end<=chapter.end&&end>start)
        val left=(start.toLong()-n).coerceAtLeast(chapter.start.toLong()).toInt();val right=(end.toLong()+n).coerceAtMost(chapter.end.toLong()).toInt()
        val ps=source.paragraphStarts;val pe=source.paragraphEnds
        val pi=lower(ps,left);val pj=lower(pe,right+1)-1
        var a=if(left==chapter.start)left else if(pi<ps.size&&ps[pi]<=start)ps[pi]else {
            val wi=lower(source.wordStarts,left);if(wi<source.wordStarts.size&&source.wordStarts[wi]<=start)source.wordStarts[wi]else start
        }
        var b=if(right==chapter.end)right else if(pj>=0&&pe[pj]>=end)pe[pj]else {
            val wi=lower(source.wordEnds,right+1)-1;if(wi>=0&&source.wordEnds[wi]>=end)source.wordEnds[wi]else end
        }
        // Selected text is never shortened, even when an old selection boundary is unusual.
        a=a.coerceIn(chapter.start,start);b=b.coerceIn(end,chapter.end)
        return ContextSpan(a,b,source.slice(a,b),a>chapter.start,b<chapter.end)
    }
    fun build(records:List<JSONObject>,n:Int=350):NoteReport {
        require(n>=0)
        val valid=mutableListOf<ReportNote>();val unresolved=mutableListOf<UnresolvedNote>()
        records.forEach{raw->try {
            val r=raw.getInt("chapter");val a=raw.getInt("start");val b=raw.getInt("end");val quote=raw.getString("quote")
            analysis.validate(raw)
            val start=source.absolute(r,a);val end=source.absolute(r,b);val chapter=analysis.chapterAt(start);val multi=chapter==null||end>chapter.end
            valid+=ReportNote(raw.getString("id"),r,a,b,start,end,quote,raw.optString("comment"),NoteColor.of(raw.optString("color")),chapter,multi,JSONObject(raw.toString()))
        }catch(_:Exception){unresolved+=UnresolvedNote(JSONObject(raw.toString()),"Привязка к сохранённому тексту не подтверждена")}}
        val sorted=valid.sortedWith(compareBy<ReportNote>{it.start}.thenBy{it.end}.thenBy{it.id})
        fun span(group:List<ReportNote>):ContextSpan {
            val a=group.minOf{it.start};val b=group.maxOf{it.end};val first=group.first()
            return if(first.multiChapter)ContextSpan(a,b,source.slice(a,b),first.chapter?.let{a>it.start}==true,analysis.chapterAt(b-1)?.let{b<it.end}==true)else context(a,b,checkNotNull(first.chapter),n)
        }
        var groups=sorted.map{listOf(it)}
        while(true){
            val parent=IntArray(groups.size){it}
            fun root(x:Int):Int{var i=x;while(parent[i]!=i){parent[i]=parent[parent[i]];i=parent[i]};return i}
            val spans=groups.map(::span)
            for(i in groups.indices)for(j in i+1 until groups.size){
                if(groups[i].first().multiChapter||groups[j].first().multiChapter||groups[i].first().chapter?.id!=groups[j].first().chapter?.id)continue
                fun hits(notes:List<ReportNote>,c:ContextSpan)=notes.any{it.start<c.end&&it.end>c.start}
                if(hits(groups[j],spans[i])||hits(groups[i],spans[j])){val a=root(i);val b=root(j);if(a!=b)parent[b]=a}
            }
            val merged=groups.indices.groupBy(::root).values.map{indices->indices.flatMap{groups[it]}.sortedWith(compareBy<ReportNote>{it.start}.thenBy{it.end}.thenBy{it.id})}.sortedBy{it.first().start}
            if(merged.size==groups.size)break
            groups=merged
        }
        val output=groups.map{g->ReportGroup(if(g.first().multiChapter)"Выделение через несколько глав"else g.first().chapter?.title ?: "Ресурс",span(g),g.mapIndexed{i,note->NumberedNote(i+1,note)})}
        return NoteReport(output,sorted,unresolved.sortedBy{it.raw.optString("id")})
    }
}
