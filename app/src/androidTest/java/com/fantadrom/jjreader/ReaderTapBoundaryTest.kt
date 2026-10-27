@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.regression.ReaderRuntimeActivity
import kotlinx.coroutines.*
import org.hamcrest.Matchers.allOf
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Native touch + real Readium. Compiled in cloud; runtime requires Android. */
@RunWith(AndroidJUnit4::class)
class ReaderTapBoundaryTest {
    private fun launch()=ActivityScenario.launch<ReaderRuntimeActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext,ReaderRuntimeActivity::class.java).putExtra("testToken",UUID.randomUUID().toString()).putExtra("p1Ui",true).putExtra("mode","sections"))
    private fun activity(s:ActivityScenario<ReaderRuntimeActivity>):ReaderRuntimeActivity{lateinit var a:ReaderRuntimeActivity;s.onActivity{a=it};return a}
    private suspend fun ready(s:ActivityScenario<ReaderRuntimeActivity>,index:Int?=null):ReaderRuntimeActivity=withTimeout(30000){while(true){val a=activity(s);if(withContext(Dispatchers.Main){a.readerSessionForTests()?.let{it.ready&&(index==null||it.chapterIndex()==index)}==true})return@withTimeout a;delay(40)};error("NOT_READY")}
    private suspend fun seed(s:ActivityScenario<ReaderRuntimeActivity>):Book {
        val a=activity(s);delay(350)
        val b=withContext(Dispatchers.IO){val store=BookStore(a);val f=store.stage(a.assets.open("p11-taps.epub"));val engine=ReadiumEngine(a);val p=engine.open(f);try{store.commit(f,"Тест P1.1",engine.chapters(p)).also{store.select(it.id)}}finally{p.close()}}
        s.recreate();ready(s,0);return b
    }
    private suspend fun js(a:ReaderRuntimeActivity,expression:String)=withContext(Dispatchers.Main){val nav=a.supportFragmentManager.findFragmentByTag("reader") as org.readium.r2.navigator.epub.EpubNavigatorFragment;LocatorBridge(a).call(nav,expression)!!}
    private fun point(x:Float,y:Float)=CoordinatesProvider{v->val p=IntArray(2);v.getLocationOnScreen(p);floatArrayOf(p[0]+v.width*x,p[1]+v.height*y)}
    private fun tap(x:Float)=onView(allOf(isAssignableFrom(WebView::class.java),isDisplayed())).perform(GeneralClickAction(Tap.SINGLE,point(x,.6f),Press.FINGER))
    private suspend fun assertEndPage(a:ReaderRuntimeActivity){val s=js(a,"JJ.contentState()");val gap=s.getDouble("freeBelow");assertEquals(minOf(4*s.getDouble("lineHeight"),s.getDouble("viewportHeight")/2),gap,2.0)}
    @Test fun oneNativeTapCrossesContentBoundaryAndEndPageSurvivesRecreateAndSixRotations()=runBlocking {
        launch().use{s->val b=seed(s);var a=ready(s,0)
            js(a,"({ok:(function(){const s=JJ.contentState();scrollTo(0,s.contentBottom+s.lineHeight+2-innerHeight);return true})()})");delay(180)
            tap(.86f);a=ready(s,1);assertEquals(0.0,js(a,"JJ.contentState()").getDouble("scrollY"),1.0);assertTrue(js(a,"JJ.contentState()").getDouble("contentTop")>1)
            tap(.16f);a=ready(s,0);assertEndPage(a)
            assertEquals("END_PAGE",NoteStore(a,b.id).data.getJSONObject("positionV2").getJSONObject("entryPlacement").getString("kind"))
            s.recreate();a=ready(s,0);assertEndPage(a)
            repeat(6){s.onActivity{it.requestedOrientation=if(it.resources.configuration.orientation==1)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};delay(650);a=ready(s,0);assertEndPage(a)}
            // Ordinary note targets must not inherit the end-page entry.
            for((index,offset) in listOf(0 to 233,1 to 20)){
                withContext(Dispatchers.Main){val range=js(a,"JJ.anchor($offset,${offset+1})").put("chapter",a.readerSessionForTests()!!.chapterIndex())
                    // For another resource derive the quote from the original text, never from current DOM.
                    val target=if(index==a.readerSessionForTests()!!.chapterIndex())range else JSONObject().put("chapter",index).put("start",offset).put("end",offset+1).put("quote",com.fantadrom.jjreader.text.TextModel.slice(b.chapters[index].text,offset,offset+1))
                    a.readerSessionForTests()!!.navigateTo(target)}
                s.recreate();a=ready(s,index);val p=NoteStore(a,b.id).data.getJSONObject("positionV2");assertEquals(offset,p.getInt("start"));assertFalse(p.has("entryPlacement"))
            }
        }
    }
    @Test fun partialLineStaysInResourceAndBackwardSwipeKeepsEndAtTop()=runBlocking {
        launch().use{s->seed(s);var a=ready(s,0)
            js(a,"({ok:(function(){const s=JJ.contentState();scrollTo(0,s.contentBottom-3-innerHeight);return true})()})");delay(180);tap(.86f);delay(450);a=ready(s,0)
            tap(.86f);a=ready(s,1)
            onView(allOf(isAssignableFrom(WebView::class.java),isDisplayed())).perform(GeneralSwipeAction(Swipe.FAST,point(.5f,.3f),point(.5f,.8f),Press.FINGER));a=ready(s,0)
            val state=js(a,"({rect:JJ.geometry(JJ.text.length-1,JJ.text.length).firstRect})");assertEquals(0.0,state.getJSONObject("rect").getDouble("top"),2.0)
        }
    }
}
