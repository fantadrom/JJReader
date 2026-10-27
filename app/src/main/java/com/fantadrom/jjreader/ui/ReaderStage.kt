package com.fantadrom.jjreader.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentContainerView

/** Cover preserves a live, laid-out WebView; no invisible/zero-size restoration geometry. */
class ReaderStage(context:Context,readerId:Int):FrameLayout(context) {
    val cover=TextView(context).apply{text="Открытие раздела…";gravity=Gravity.CENTER;isClickable=true;isFocusable=true;setBackgroundColor(0xFFF7F3EB.toInt());visibility=View.GONE}
    init{addView(FragmentContainerView(context).apply{id=readerId},LayoutParams(-1,-1));addView(cover,LayoutParams(-1,-1))}
}
