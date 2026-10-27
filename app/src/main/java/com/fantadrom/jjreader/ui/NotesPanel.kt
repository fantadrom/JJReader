package com.fantadrom.jjreader.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.*
import android.text.style.*
import android.view.View
import android.widget.*
import com.fantadrom.jjreader.notes.*
import com.fantadrom.jjreader.report.*
import kotlinx.coroutines.*

/** P2 viewers consume the same report model that P3 will render. No export/clipboard implementation. */
class NotesPanel(private val context:Context,private val scope:CoroutineScope,private val store:NoteStore,private val analysis:BookAnalysis,
                 private val jump:((ReportNote)->Unit)?,private val edit:(org.json.JSONObject)->Unit,private val changed:()->Unit,private val closeLabel:String="К чтению") {
    private val rows=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val scroll=ScrollView(context).apply{addView(rows)}
    private val number=EditText(context).apply{setText(store.contextN.toString());inputType=android.text.InputType.TYPE_CLASS_NUMBER;setSingleLine();contentDescription="Символов контекста с каждой стороны"}
    private val resumeDraft=Button(context).apply{text="Продолжить черновик";isAllCaps=false;setOnClickListener{store.data.optJSONObject("draft")?.let{val saved=org.json.JSONObject(it.toString());dialog.dismiss();edit(saved)}}}
    private val undo=Button(context).apply{text="Отменить удаление";isAllCaps=false;setOnClickListener{runCatching{store.undoDelete()}.onSuccess{changed();refresh()}.onFailure{error(it)}}}
    private var grouped=false;private var job:Job?=null
    private var closed:(()->Unit)?=null
    private val builder=ReportBuilder(analysis)
    private val dialog:AlertDialog
    init {
        val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(16,0,16,0)}
        body.addView(LinearLayout(context).apply{addView(TextView(context).apply{text="Контекст N:"});addView(number,LinearLayout.LayoutParams(0,-2,1f));addView(button("Применить"){val n=number.text.toString().toIntOrNull();if(n==null||n<0)number.error="Введите целое N ≥ 0"else runCatching{store.setContextN(n)}.onSuccess{refresh()}.onFailure{error(it)}})})
        body.addView(LinearLayout(context).apply{addView(button("Пометки"){grouped=false;refresh()},LinearLayout.LayoutParams(0,-2,1f));addView(button("Контексты"){grouped=true;refresh()},LinearLayout.LayoutParams(0,-2,1f))})
        body.addView(LinearLayout(context).apply{addView(resumeDraft,LinearLayout.LayoutParams(0,-2,1f));addView(undo,LinearLayout.LayoutParams(0,-2,1f))});body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        dialog=AlertDialog.Builder(context).setTitle("Пометки · ${analysis.source.book.title}").setView(body).setNegativeButton(closeLabel,null).create()
        dialog.setOnDismissListener{job?.cancel();closed?.invoke()}
    }
    fun show(){dialog.show();dialog.window?.setLayout(-1,(context.resources.displayMetrics.heightPixels*.86).toInt());refresh()}
    val showing get()=dialog.isShowing
    fun dismiss(){dialog.dismiss()}
    fun onClosed(callback:()->Unit){closed=callback}
    fun refresh(){
        if(!dialog.isShowing)return
        job?.cancel();val records=store.notes.map{org.json.JSONObject(it.toString())};val n=store.contextN
        undo.visibility=if(store.data.has("deletedNote"))View.VISIBLE else View.GONE
        resumeDraft.visibility=if(store.data.has("draft"))View.VISIBLE else View.GONE
        job=scope.launch{try{val report=withContext(Dispatchers.Default){builder.build(records,n)};if(!dialog.isShowing)return@launch
            rows.removeAllViews()
            if(records.isEmpty())rows.addView(text("Пометок пока нет. Выделите текст в книге и добавьте комментарий или цвет."))
            if(grouped)report.groups.forEach{g->
                rows.addView(text(g.chapterTitle, true));rows.addView(TextView(context).apply{text=render(g);textSize=16f;setTextIsSelectable(true);setPadding(4,12,4,12)})
                g.notes.forEach{numbered->row(numbered.note,"[${numbered.number}] ")}
            }else report.notes.forEach{row(it,"")}
            report.unresolved.forEach{u->rows.addView(text("${u.reason}\n${u.raw.optString("quote")}\n${u.raw.optString("comment")}"));rows.addView(button("Изменить сохранённую пометку"){dialog.dismiss();edit(org.json.JSONObject(u.raw.toString()))})}
        }catch(e:Exception){if(e is CancellationException)throw e;error(e)}}
    }
    private fun row(note:ReportNote,prefix:String){
        rows.addView(text(if(note.multiChapter)"Несколько логических глав"else note.chapter?.title ?: "Ресурс",true))
        rows.addView(text(prefix+note.quote+"\n"+listOfNotNull(note.color?.label,note.comment.takeIf{it.isNotEmpty()}).joinToString(" · ")))
        rows.addView(LinearLayout(context).apply{
            addView(button("Читать"){dialog.dismiss();jump?.invoke(note)}.apply{isEnabled=jump!=null},LinearLayout.LayoutParams(0,-2,1f))
            addView(button("Изменить"){dialog.dismiss();edit(org.json.JSONObject(note.raw.toString()))},LinearLayout.LayoutParams(0,-2,1f))
            addView(button("Удалить"){runCatching{store.delete(note.id)}.onSuccess{changed();refresh()}.onFailure{error(it)}}.apply{tag="delete-${note.id}"},LinearLayout.LayoutParams(0,-2,1f))
        })
    }
    private fun render(group:ReportGroup):CharSequence {
        val c=group.context;val out=SpannableStringBuilder(if(c.leadingEllipsis)"…"else "")
        val offsets=IntArray(c.text.codePointCount(0,c.text.length)+1)
        val ends=group.notes.groupBy{it.note.end-c.start}
        val points=c.text.codePoints().toArray()
        for(i in points.indices){offsets[i]=out.length;out.append(String(Character.toChars(points[i])));ends[i+1]?.forEach{val a=out.length;out.append("[${it.number}]");out.setSpan(SuperscriptSpan(),a,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);out.setSpan(RelativeSizeSpan(.75f),a,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}}
        offsets[points.size]=out.length
        group.notes.forEach{v->val a=(v.note.start-c.start).coerceIn(0,points.size);val b=(v.note.end-c.start).coerceIn(a,points.size);val from=offsets[a];val to=offsets[b];if(from<to){out.setSpan(BackgroundColorSpan(v.note.color?.rgb ?: 0x55888888),from,to,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);if(v.note.color!=null)out.setSpan(ForegroundColorSpan(android.graphics.Color.BLACK),from,to,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}}
        if(c.trailingEllipsis)out.append("…");return out
    }
    private fun text(value:String,bold:Boolean=false)=TextView(context).apply{text=value;textSize=if(bold)15f else 16f;setPadding(4,10,4,4);if(bold)setTypeface(null,Typeface.BOLD)}
    private fun button(value:String,action:()->Unit)=Button(context).apply{text=value;isAllCaps=false;textSize=12f;minWidth=0;setOnClickListener{action()}}
    private fun error(e:Throwable){Toast.makeText(context,e.message ?: "Не удалось сохранить изменение",Toast.LENGTH_LONG).show()}
}
