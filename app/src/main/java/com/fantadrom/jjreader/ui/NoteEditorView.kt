package com.fantadrom.jjreader.ui

import android.content.Context
import android.widget.*
import com.fantadrom.jjreader.notes.NoteColor
import org.json.JSONObject

class NoteEditorView(context:Context,val draft:JSONObject,onChange:()->Unit):LinearLayout(context) {
    val field=EditText(context).apply{hint="Короткий комментарий";setText(draft.optString("comment"));minLines=2;setSelection(text.length)}
    init {
        orientation=VERTICAL;setPadding(20,8,20,0)
        addView(TextView(context).apply{text=draft.optString("quote");maxLines=5;textSize=16f})
        addView(TextView(context).apply{text="Цвет выделения"})
        val colors=listOf("Без цвета")+NoteColor.entries.map{it.label}
        val spinner=Spinner(context).apply{contentDescription="Цвет выделения";adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,colors);setSelection(NoteColor.entries.indexOf(NoteColor.of(draft.optString("color")))+1)}
        addView(spinner,LayoutParams((220*resources.displayMetrics.density).toInt(),-2))
        spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
            override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long){val key=NoteColor.entries.getOrNull(position-1)?.key;if(draft.optString("color").takeIf{it.isNotEmpty()}!=key){draft.put("color",key);onChange()}}
        }
        addView(field)
    }
}
