package com.fantadrom.jjreader.engine

import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.doOnPreDraw
import com.fantadrom.jjreader.ui.ReaderStage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

fun interface ReaderFrames { suspend fun await(href:String,valid:()->Boolean,onVisual:()->Unit) }

private class WebViewFrames(private val stage:ReaderStage,private val views:()->View?):ReaderFrames {
    private fun candidates(root:View?):List<WebView> = when(root){is WebView->listOf(root);is ViewGroup->(0 until root.childCount).flatMap{candidates(root.getChildAt(it))};else->emptyList()}
    override suspend fun await(href:String,valid:()->Boolean,onVisual:()->Unit){
        val path=Uri.parse(href).path?.trimStart('/');var web:WebView?=null
        while(web==null){
            if(!valid())throw StaleReaderOperation()
            web=candidates(views()).filter{it.isShown&&it.width>0&&it.height>0&&Uri.parse(it.url ?: "").path?.trimStart('/')==path&&it.getGlobalVisibleRect(Rect())}.maxByOrNull{val r=Rect();it.getGlobalVisibleRect(r);r.width().toLong()*r.height()}
            if(web==null)delay(16)
        }
        val currentWeb=web
        suspendCancellableCoroutine<Unit>{continuation->
            currentWeb.postVisualStateCallback(System.nanoTime(),object:WebView.VisualStateCallback(){override fun onComplete(requestId:Long){
                if(!continuation.isActive)return
                if(!valid()){continuation.cancel(StaleReaderOperation());return}
                onVisual()
                stage.doOnPreDraw{if(continuation.isActive){if(valid())continuation.resume(Unit)else continuation.cancel(StaleReaderOperation())}};stage.invalidate()
            }})
        }
    }
}

/** ACK then compositor-ready then pre-draw. No timed reveal; stale callbacks cannot uncover a new operation. */
class ReaderRevealGate(private val stage:ReaderStage,views:()->View?,private val trace:(String,Int)->Unit,
                       private val frames:ReaderFrames=WebViewFrames(stage,views)) {
    private var serial=0L;private var target=-1;private val mutex=Mutex()
    var pending=false;private set
    var failed=false;private set
    fun begin(index:Int){serial++;target=index;pending=true;failed=false;stage.cover.text="Открытие раздела…";stage.cover.visibility=View.VISIBLE;trace("PRESENT_HIDE",index)}
    fun failure(){if(pending){failed=true;stage.cover.text="Не удалось открыть место. Выберите раздел через меню."}}
    fun cancel(){serial++;pending=false;failed=false;stage.cover.visibility=View.GONE}
    suspend fun present(index:Int,href:String,valid:()->Boolean)=mutex.withLock {
        if(!pending)return@withLock
        if(!valid())throw StaleReaderOperation()
        // A cancelled navigation may recover a verified visible resource different from its old target.
        if(target!=index)begin(index)
        val token=serial
        fun current()=valid()&&pending&&target==index&&serial==token
        if(!current())throw StaleReaderOperation()
        try{withTimeout(6000){frames.await(href,::current){trace("PRESENT_VISUAL_READY",index)}
            if(!current())throw StaleReaderOperation()
            stage.cover.visibility=View.GONE;pending=false;trace("PRESENT_REVEAL",index)
        }}catch(e:Exception){if(serial==token)failure();throw e}
    }
}
