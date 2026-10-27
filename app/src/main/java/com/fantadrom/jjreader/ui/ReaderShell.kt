package com.fantadrom.jjreader.ui

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.View
import android.widget.*
import androidx.fragment.app.FragmentContainerView
import com.fantadrom.jjreader.books.BookEntry
import java.text.DateFormat
import java.util.Date

/** Views shell around the existing Fragment. No publication or persistence ownership. */
class ReaderShell(private val context:Context,readerId:Int,menu:()->Unit,books:()->Unit,back:()->Unit,folder:()->Unit,openFile:()->Unit,sample:()->Unit,resume:()->Unit,
                  private val select:(BookEntry)->Unit,private val manage:(BookEntry)->Unit) {
    val root=FrameLayout(context)
    val reader=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    val heading=TextView(context).apply{text="JJReader";textSize=17f;maxLines=1;ellipsize=TextUtils.TruncateAt.END}
    val progress=TextView(context).apply{text="0%";textSize=14f}
    val status=TextView(context).apply{textSize=12f;maxLines=2;setPadding(dp(12),0,dp(12),dp(4));accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
    val slider=SeekBar(context).apply{max=10000;contentDescription="Положение во всей книге"}
    private val resumeButton=button("К чтению",resume).apply{isEnabled=false}
    private val previous=button("Вернуться",back)
    private val top=LinearLayout(context).apply{gravity=android.view.Gravity.CENTER_VERTICAL;addView(button("Книги",books));addView(heading,LinearLayout.LayoutParams(0,-2,1f));addView(button("Меню",menu))}
    private val bottom=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;addView(LinearLayout(context).apply{gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(8),0);addView(progress,LinearLayout.LayoutParams(0,-2,1f));addView(previous)});addView(slider);addView(status)}
    private val shelf=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),0)}
    private val shelfMessage=TextView(context).apply{textSize=14f;setPadding(0,dp(8),0,dp(8))}
    private val rows=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    var onShelf=true;private set
    var panels=true;private set
    init {
        reader.addView(top);reader.addView(FragmentContainerView(context).apply{id=readerId},LinearLayout.LayoutParams(-1,0,1f));reader.addView(bottom)
        shelf.addView(TextView(context).apply{text="Книги";textSize=24f})
        shelf.addView(LinearLayout(context).apply{addView(button("Папка EPUB",folder),LinearLayout.LayoutParams(0,-2,1f));addView(button("Открыть файл",openFile),LinearLayout.LayoutParams(0,-2,1f))})
        shelf.addView(LinearLayout(context).apply{addView(button("Пример P0",sample),LinearLayout.LayoutParams(0,-2,1f));addView(resumeButton,LinearLayout.LayoutParams(0,-2,1f))})
        shelf.addView(shelfMessage);shelf.addView(ScrollView(context).apply{addView(rows)},LinearLayout.LayoutParams(-1,0,1f))
        root.addView(reader,FrameLayout.LayoutParams(-1,-1));root.addView(shelf,FrameLayout.LayoutParams(-1,-1));showShelf()
    }
    fun bookAvailable(available:Boolean){resumeButton.isEnabled=available}
    fun message(value:String){shelfMessage.text=value}
    fun showReader(){onShelf=false;shelf.visibility=View.GONE;reader.visibility=View.VISIBLE}
    fun showShelf(){onShelf=true;shelf.visibility=View.VISIBLE;reader.visibility=View.INVISIBLE}
    fun setPanels(visible:Boolean){panels=visible;top.visibility=if(visible)View.VISIBLE else View.GONE;bottom.visibility=if(visible)View.VISIBLE else View.GONE}
    fun previousAvailable(available:Boolean){previous.isEnabled=available}
    fun shelf(entries:List<BookEntry>,message:String="") {
        showShelf();shelfMessage.text=message.ifBlank{if(entries.isEmpty())"Книг пока нет. Выберите стартовую папку или EPUB. Внутренние копии не зависят от исходных файлов."else"${entries.size} внутренних копий. Пометки и позиции сохраняются отдельно."}
        rows.removeAllViews()
        entries.forEach{entry->
            val title=(if(entry.current)"● "else"")+entry.title
            val details=listOfNotNull(entry.sourceName ?: entry.importedAt.takeIf{it>0}?.let{DateFormat.getDateInstance().format(Date(it))},entry.progress?.let{"$it%"} ?: if(entry.savedPlace)"Место сохранено"else"Не начата","Пометок: ${entry.notes}",entry.id.take(8),if(!entry.available)"Недоступна"else null).joinToString(" · ")
            rows.addView(LinearLayout(context).apply{gravity=android.view.Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(12),dp(4),dp(12));isClickable=true;isFocusable=true;contentDescription="$title. $details";setOnClickListener{select(entry)};addView(TextView(context).apply{text=title;textSize=18f});addView(TextView(context).apply{text=details;textSize=12f})},LinearLayout.LayoutParams(0,-2,1f))
                addView(button("Действия"){manage(entry)})
            })
        }
    }
    fun theme(dark:Boolean){val background=if(dark)0xFF17191C.toInt()else 0xFFF7F3EB.toInt();val text=if(dark)Color.rgb(229,231,235)else Color.rgb(35,39,44);root.setBackgroundColor(background)
        fun paint(view:View){if(view is TextView)view.setTextColor(text);if(view is Button){view.backgroundTintList=android.content.res.ColorStateList.valueOf(if(dark)Color.rgb(52,59,71)else Color.rgb(224,217,206));view.setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(if(dark)Color.GRAY else Color.DKGRAY,text)))};if(view is android.view.ViewGroup)for(i in 0 until view.childCount)paint(view.getChildAt(i))};paint(root)
    }
    private fun button(text:String,action:()->Unit)=Button(context).apply{this.text=text;textSize=12f;isAllCaps=false;minWidth=0;setOnClickListener{action()}}
    private fun dp(value:Int)=(value*context.resources.displayMetrics.density).toInt()
}
