@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matchers.*
import com.fantadrom.jjreader.regression.ReaderRuntimeActivity
import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.notes.NoteStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Actual P1 shell and stable Readium. No legacy prototype controls. */
@RunWith(AndroidJUnit4::class)
class ReaderP1Test {
    private fun launch()=ActivityScenario.launch<ReaderRuntimeActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext,ReaderRuntimeActivity::class.java).putExtra("testToken",UUID.randomUUID().toString()).putExtra("p1Ui",true).putExtra("mode","sections"))
    private fun activity(s:ActivityScenario<ReaderRuntimeActivity>):ReaderRuntimeActivity{lateinit var a:ReaderRuntimeActivity;s.onActivity{a=it};return a}
    private suspend fun ready(s:ActivityScenario<ReaderRuntimeActivity>)=withTimeout(30000){while(true){val a=activity(s);if(withContext(Dispatchers.Main){a.readerSessionForTests()?.ready==true})return@withTimeout a;delay(50)};error("NOT_READY")}
    private suspend fun seed(s:ActivityScenario<ReaderRuntimeActivity>):Book {
        val a=activity(s);delay(500)
        val book=withContext(Dispatchers.IO){val store=BookStore(a);val file=store.stage(a.assets.open("p0-sample.epub"));val engine=ReadiumEngine(a);val pub=engine.open(file);try{store.commit(file,"Книга P1",engine.chapters(pub),"old-file.epub")}finally{pub.close()}}
        withContext(Dispatchers.IO){
            val quote=com.fantadrom.jjreader.text.TextModel.slice(book.chapters[0].text,0,5)
            val locator=JSONObject().put("href",book.chapters[0].href).put("type","application/xhtml+xml").put("locations",JSONObject().put("cssSelector","html > body > h1")).put("text",JSONObject().put("highlight",quote))
            NoteStore(a,book.id).add(JSONObject().put("id","old-p0-note").put("bookId",book.id).put("chapter",0).put("start",0).put("end",5).put("quote",quote).put("comment","Старый комментарий").put("locator",locator).put("textHash",com.fantadrom.jjreader.text.TextModel.hash(book.chapters[0].text.toByteArray())))
        }
        withContext(Dispatchers.Main){a.shelfForTests()};delay(400);onView(allOf(withText(containsString(book.title)),isDisplayed())).perform(click());ready(s);return book
    }
    @Test fun panelsSliderReturnAndSixRotationsKeepSourceTextAnchor()=runBlocking {
        launch().use{s->val book=seed(s);var a=ready(s)
            val nav=a.supportFragmentManager.findFragmentByTag("reader") as org.readium.r2.navigator.epub.EpubNavigatorFragment
            withContext(Dispatchers.Main){val range=LocatorBridge(a).call(nav,"JJ.anchor(1000,1001)")!!.put("chapter",0);a.readerSessionForTests()!!.navigateTo(range)}
            val expected=NoteStore(a,book.id).data.getJSONObject("positionV2").getInt("start")
            repeat(6){onView(allOf(isAssignableFrom(android.webkit.WebView::class.java),isDisplayed())).perform(click());delay(300);a=ready(s);assertEquals(expected,NoteStore(a,book.id).data.getJSONObject("positionV2").getInt("start"))}
            withContext(Dispatchers.Main){a.seekForTests(9500)};delay(500);a=ready(s);assertTrue(NoteStore(a,book.id).data.getJSONObject("positionV2").getInt("chapter")>0)
            withContext(Dispatchers.Main){a.returnForTests()};delay(500);a=ready(s);assertEquals(0,NoteStore(a,book.id).data.getJSONObject("positionV2").getInt("chapter"));assertEquals(expected,NoteStore(a,book.id).data.getJSONObject("positionV2").getInt("start"))
            repeat(6){s.onActivity{it.requestedOrientation=if(it.resources.configuration.orientation==1)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};delay(700);a=ready(s);assertEquals(expected,NoteStore(a,book.id).data.getJSONObject("positionV2").getInt("start"))}
            s.recreate();a=ready(s);assertEquals(expected,NoteStore(a,book.id).data.getJSONObject("positionV2").getInt("start"))
        }
    }
    @Test fun twoInternalBooksKeepIndependentPositionsAfterSwitchAndRestart()=runBlocking {
        launch().use{s->val first=seed(s);var a=ready(s)
            val second=withContext(Dispatchers.IO){val store=BookStore(a);store.commit(store.stage(a.assets.open("p0-sample.epub")),"Книга B",first.chapters,"second.epub")}
            suspend fun point(offset:Int){a=ready(s);withContext(Dispatchers.Main){val nav=a.supportFragmentManager.findFragmentByTag("reader") as org.readium.r2.navigator.epub.EpubNavigatorFragment;a.readerSessionForTests()!!.navigateTo(LocatorBridge(a).call(nav,"JJ.anchor($offset,${offset+1})")!!.put("chapter",0))}}
            suspend fun choose(book:Book){a=ready(s);withContext(Dispatchers.Main){a.shelfForTests()};delay(300);onView(allOf(withText(containsString(book.title)),isDisplayed())).perform(click());a=ready(s)}
            point(1000);choose(second);point(1500);choose(first);assertEquals(1000,NoteStore(a,first.id).data.getJSONObject("positionV2").getInt("start"))
            choose(second);assertEquals(1500,NoteStore(a,second.id).data.getJSONObject("positionV2").getInt("start"));s.recreate();a=ready(s);assertEquals(second.id,BookStore(a).current()!!.id);assertEquals(1500,NoteStore(a,second.id).data.getJSONObject("positionV2").getInt("start"))
            assertEquals(1,NoteStore(a,first.id).notes.size)
        }
    }
    @Test fun actualBrightnessSwipeAtEdgeDoesNotScrollOrChangeResource()=runBlocking {
        launch().use{s->val book=seed(s);val a=ready(s)
            val nav=a.supportFragmentManager.findFragmentByTag("reader") as org.readium.r2.navigator.epub.EpubNavigatorFragment
            withContext(Dispatchers.Main){a.window.attributes=a.window.attributes.apply{screenBrightness=.3f};a.readerSessionForTests()!!.navigateBoundary(0,true,ReadingTrace.Source.NATIVE)};delay(300)
            val y=withContext(Dispatchers.Main){LocatorBridge(a).call(nav,"JJ.contentState()")!!.getDouble("scrollY")}
            fun point(x:Float,y:Float)=androidx.test.espresso.action.CoordinatesProvider{v->val p=IntArray(2);v.getLocationOnScreen(p);floatArrayOf(p[0]+v.width*x,p[1]+v.height*y)}
            onView(allOf(isAssignableFrom(android.webkit.WebView::class.java),isDisplayed())).perform(androidx.test.espresso.action.GeneralSwipeAction(androidx.test.espresso.action.Swipe.FAST,point(.03f,.8f),point(.03f,.2f),androidx.test.espresso.action.Press.FINGER));delay(400)
            assertTrue(a.window.attributes.screenBrightness>.3f);assertEquals(0,a.readerSessionForTests()!!.chapterIndex())
            val after=withContext(Dispatchers.Main){LocatorBridge(a).call(nav,"JJ.contentState()")!!.getDouble("scrollY")};assertEquals(y,after,2.0)
            onView(allOf(isAssignableFrom(android.webkit.WebView::class.java),isDisplayed())).perform(click());delay(450);onView(withText("Меню")).check(androidx.test.espresso.assertion.ViewAssertions.matches(not(isDisplayed())))
            onView(allOf(isAssignableFrom(android.webkit.WebView::class.java),isDisplayed())).perform(click());delay(450);onView(withText("Меню")).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()))
            assertEquals(book.id,BookStore(a).current()!!.id)
        }
    }
    @Test fun shelfClearAndDeleteDialogsCancelWithoutLosingBookOrPosition()=runBlocking {
        launch().use{s->val book=seed(s);var a=ready(s)
            withContext(Dispatchers.Main){a.shelfForTests()};delay(400)
            onView(withText("Действия")).perform(click());onView(withText("Удалить книгу")).perform(click());onView(withText("Отмена")).perform(click());assertTrue(book.file.exists());assertEquals(1,NoteStore(a,book.id).notes.size)
            onView(withText("Действия")).perform(click());onView(withText("Очистить пометки")).perform(click());onView(withText("Отмена")).perform(click());assertTrue(book.file.exists());assertEquals(1,NoteStore(a,book.id).notes.size)
            onView(allOf(withText(containsString(book.title)),isDisplayed())).perform(click());a=ready(s);val place=JSONObject(NoteStore(a,book.id).data.getJSONObject("positionV2").toString())
            withContext(Dispatchers.Main){a.shelfForTests()};delay(300);onView(withText("Действия")).perform(click());onView(withText("Очистить пометки")).perform(click());onView(withText("Очистить")).perform(click());delay(300)
            val state=NoteStore(a,book.id).data;assertEquals(0,state.getJSONArray("notes").length());assertEquals(place.getInt("start"),state.getJSONObject("positionV2").getInt("start"));assertTrue(book.file.exists())
            onView(withText("Действия")).perform(click());onView(withText("Удалить книгу")).perform(click());onView(withText("Удалить")).perform(click());delay(400);assertFalse(book.file.exists());assertNull(BookStore(a).current())
        }
    }
}
