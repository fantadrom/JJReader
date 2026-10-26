@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.action.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.*
import com.fantadrom.jjreader.regression.ReaderRuntimeActivity
import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.notes.NoteStore
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.util.UUID

/** Real MotionEvent gestures and MediaPlayer WAV fixture, not Microsoft/network proof. */
@RunWith(AndroidJUnit4::class)
class SectionNavigationTest {
    private fun activity(s:ActivityScenario<ReaderRuntimeActivity>):ReaderRuntimeActivity{lateinit var a:ReaderRuntimeActivity;s.onActivity{a=it};return a}
    private suspend fun ready(s:ActivityScenario<ReaderRuntimeActivity>,chapter:Int?=null)=withTimeout(30000){while(true){val a=activity(s);if(withContext(Dispatchers.Main){a.readerSessionForTests()?.let{it.ready&&(chapter==null||it.chapterIndex()==chapter)}==true})return@withTimeout a;delay(50)};error("NOT_READY")}
    private fun nav(a:ReaderRuntimeActivity)=a.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
    private fun launch()=ActivityScenario.launch<ReaderRuntimeActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext,ReaderRuntimeActivity::class.java).putExtra("testToken",UUID.randomUUID().toString()).putExtra("mode","sections").putExtra("localSpeech",true))
    private suspend fun seedAndOpen(s:ActivityScenario<ReaderRuntimeActivity>):Book {
        val a=activity(s);val store=BookStore(a);val engine=ReadiumEngine(a)
        val book=withContext(Dispatchers.IO){val file=store.stage(InstrumentationRegistry.getInstrumentation().context.assets.open("p08-sections.epub"));val pub=engine.open(file);try{store.commit(file,"Проверка разделов",engine.chapters(pub))}finally{pub.close()}}
        onView(withText("Книги")).perform(click());onView(withText(containsString(book.id.take(8)))).perform(click());ready(s,0);return book
    }
    private fun swipe(up:Boolean){onView(allOf(isAssignableFrom(WebView::class.java),isDisplayed())).perform(GeneralSwipeAction(Swipe.FAST,CoordinatesProvider{v->val p=IntArray(2);v.getLocationOnScreen(p);floatArrayOf(p[0]+v.width*.5f,p[1]+v.height*(if(up).8f else .2f))},CoordinatesProvider{v->val p=IntArray(2);v.getLocationOnScreen(p);floatArrayOf(p[0]+v.width*.5f,p[1]+v.height*(if(up).2f else .8f))},Press.FINGER))}
    @Test fun realEdgeIntentReturnShortImageEmptyAndSixRotations()=runBlocking {
        launch().use{s->val book=seedAndOpen(s);var a=ready(s,0)
            withContext(Dispatchers.Main){LocatorBridge(a).call(nav(a),"({ok:scrollTo(0,document.documentElement.scrollHeight-200)})")};delay(200)
            swipe(true);delay(300);assertEquals(0,withContext(Dispatchers.Main){a.readerSessionForTests()!!.chapterIndex()})
            // The gesture reaching the edge must not transition. Establish exact edge and allow inertia to settle.
            withContext(Dispatchers.Main){LocatorBridge(a).call(nav(a),"({ok:scrollTo(0,document.documentElement.scrollHeight)})")};delay(250)
            swipe(true);a=ready(s,1)
            withContext(Dispatchers.Main){LocatorBridge(a).call(nav(a),"({ok:scrollTo(0,0)})")};delay(250);swipe(false);a=ready(s,0)
            val end=withContext(Dispatchers.Main){NoteStore(a,book.id).data.getJSONObject("positionV2").getInt("start")}
            repeat(6){s.onActivity{it.requestedOrientation=if(it.resources.configuration.orientation==1)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};delay(750);a=ready(s,0);assertEquals(end,withContext(Dispatchers.Main){NoteStore(a,book.id).data.getJSONObject("positionV2").getInt("start")})}
            onView(withText("Раздел →")).perform(click());ready(s,1);onView(withText("Раздел →")).perform(click());a=ready(s,2)
            assertEquals("visual",withContext(Dispatchers.Main){NoteStore(a,book.id).data.getString("positionKind")})
            assertTrue(withContext(Dispatchers.Main){LocatorBridge(a).call(nav(a),"({ok:Array.from(document.images).every(i=>i.complete&&i.naturalWidth>0)})")!!.getBoolean("ok")})
            onView(withText("Раздел →")).perform(click());a=ready(s,3);s.recreate();ready(s,3)
            onView(withText("Раздел →")).perform(click());ready(s,4);swipe(true);delay(250);assertEquals(4,activity(s).readerSessionForTests()!!.chapterIndex())
        }
    }
    @Test fun orphanNoteBearingCopyAndSampleDedupUseExistingIDs()=runBlocking {
        launch().use{s->val a=activity(s);delay(800)
            val store=BookStore(a);val engine=ReadiumEngine(a)
            val copies=withContext(Dispatchers.IO){
                val file=store.stage(a.assets.open("p0-sample.epub"));val pub=engine.open(file)
                val chapters=try{engine.chapters(pub)}finally{pub.close()}
                val first=store.commit(file,"Прежняя книга",chapters)
                val second=store.commit(store.stage(a.assets.open("p0-sample.epub")),"Прежняя книга",chapters)
                val anchor=org.json.JSONObject().put("bookId",first.id).put("id","old-note").put("chapter",0).put("start",10).put("end",20)
                    .put("quote",com.fantadrom.jjreader.text.TextModel.slice(chapters[0].text,10,20)).put("comment","Старый комментарий")
                    .put("textHash",com.fantadrom.jjreader.text.TextModel.hash(chapters[0].text.toByteArray()))
                    .put("locator",org.json.JSONObject().put("href",chapters[0].href).put("type","application/xhtml+xml"))
                NoteStore(a,first.id).apply{data.getJSONArray("notes").put(anchor);data.put("positionV2",anchor).put("fontSize",1.3);save()}
                store.select(second.id);first to second
            }
            onView(withText("Книги")).perform(click());onView(withText(containsString(copies.first.id.take(8)))).perform(click());ready(s,0)
            val oldNotes=NoteStore(activity(s),copies.first.id).data.getJSONArray("notes").toString()
            assertEquals(copies.first.id,store.current()!!.id)
            onView(withText("Пример P0")).perform(click());onView(withText(containsString(copies.first.id.take(8)))).perform(click());ready(s,0)
            assertEquals(2,store.entries().size);assertEquals(oldNotes,NoteStore(activity(s),copies.first.id).data.getJSONArray("notes").toString())
            onView(withText("Пример P0")).perform(click());onView(withText("Отмена")).perform(click());assertEquals(copies.first.id,store.current()!!.id)
            onView(withText("Книги")).perform(click());onView(withText(containsString(copies.second.id.take(8)))).perform(click());ready(s)
            onView(withText("Книги")).perform(click());onView(withText(containsString(copies.first.id.take(8)))).perform(click());ready(s)
            assertEquals(oldNotes,NoteStore(activity(s),copies.first.id).data.getJSONArray("notes").toString());assertEquals(2,store.entries().size)
        }
    }
    @Test fun fixtureAudioUsesNormalQueueAndNavigatorAcrossBoundaryAndPause()=runBlocking {
        launch().use{s->val book=seedAndOpen(s);val a=ready(s,0)
            withContext(Dispatchers.Main){val bridge=LocatorBridge(a);val range=bridge.call(nav(a),"JJ.anchorForId('end')")!!.put("chapter",0);a.readerSessionForTests()!!.navigateTo(range);a.startSpeechForTests()}
            withTimeout(30000){while(withContext(Dispatchers.Main){a.readerSessionForTests()!!.chapterIndex()}==0)delay(50)}
            val trace=BookStore.readAtomic(java.io.File(a.filesDir,"section-navigation.json")).toString();assertTrue(trace.contains("TTS"));assertFalse(trace.contains(book.title))
            onView(withText("Пауза")).perform(click());val current=a.readerSessionForTests()!!.chapterIndex();delay(800);assertEquals(current,a.readerSessionForTests()!!.chapterIndex())
            onView(withText("Продолжить")).perform(click());delay(1000)
            onView(withText("← Раздел")).perform(click());ready(s);onView(withText("Продолжить")).perform(click());delay(800)
        }
    }
}
