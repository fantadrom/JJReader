@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.content.Intent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.*
import androidx.test.espresso.action.*
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.notes.*
import com.fantadrom.jjreader.regression.ReaderRuntimeActivity
import kotlinx.coroutines.*
import org.hamcrest.Matchers.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderP2Test {
    private fun launch()=ActivityScenario.launch<ReaderRuntimeActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext,ReaderRuntimeActivity::class.java).putExtra("testToken",UUID.randomUUID().toString()).putExtra("p1Ui",true).putExtra("mode","sections"))
    private fun activity(s:ActivityScenario<ReaderRuntimeActivity>):ReaderRuntimeActivity{lateinit var a:ReaderRuntimeActivity;s.onActivity{a=it};return a}
    private suspend fun ready(s:ActivityScenario<ReaderRuntimeActivity>,index:Int?=null)=withTimeout(30000){while(true){val a=activity(s);if(withContext(Dispatchers.Main){a.readerSessionForTests()?.let{it.ready&&(index==null||it.chapterIndex()==index)}==true})return@withTimeout a;delay(40)};error("NOT_READY")}
    private suspend fun seed(s:ActivityScenario<ReaderRuntimeActivity>):Book {
        val a=activity(s);delay(400)
        val b=withContext(Dispatchers.IO){val store=BookStore(a);val f=store.stage(a.assets.open("p2-logical.epub"));val e=ReadiumEngine(a);val p=e.open(f);try{store.commit(f,"P2 тест",e.chapters(p)).also{store.select(it.id)}}finally{p.close()}}
        s.recreate();ready(s,0);return b
    }
    private suspend fun range(a:ReaderRuntimeActivity,start:Int,end:Int)=withContext(Dispatchers.Main){val nav=a.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment;LocatorBridge(a).call(nav,"JJ.anchor($start,$end)")!!.put("chapter",0)}
    @Test fun oldNoteEditColorOnlyUndoContextNAndListReturnUseRealP2Ui()=runBlocking {
        launch().use{s->val b=seed(s);var a=ready(s)
            val old=range(a,40,50).put("id","old-p0").put("bookId",b.id).put("comment","Старый комментарий")
            withContext(Dispatchers.Main){val nav=a.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment;old.put("locator",LocatorBridge(a).locator(nav.currentLocator.value,old).toJSON());NoteStore(a,b.id).add(old)}
            s.recreate();a=ready(s)
            onView(withText("Меню")).perform(click());onView(withText("Пометки · 1")).perform(click());onView(withText("Изменить")).perform(click());
            onView(withHint("Короткий комментарий")).perform(replaceText("Несохранённый черновик"));androidx.test.espresso.Espresso.closeSoftKeyboard();androidx.test.espresso.Espresso.pressBack();a=ready(s)
            assertEquals("Несохранённый черновик",NoteStore(a,b.id).data.getJSONObject("draft").getString("comment"))
            onView(withText("Меню")).perform(click());onView(withText("Пометки · 1")).perform(click());onView(withText("Продолжить черновик")).perform(click())
            onView(withHint("Короткий комментарий")).check(matches(withText("Несохранённый черновик"))).perform(replaceText("Исправленный комментарий"));androidx.test.espresso.Espresso.closeSoftKeyboard();onView(withText("Сохранить")).perform(click());a=ready(s)
            assertEquals(1,NoteStore(a,b.id).notes.size);assertEquals("old-p0",NoteStore(a,b.id).notes.single().getString("id"));assertEquals("Исправленный комментарий",NoteStore(a,b.id).notes.single().getString("comment"))
            // New color-only note uses selection ActionMode and one named palette choice.
            val nav=a.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
            withContext(Dispatchers.Main){a.readerSessionForTests()!!.navigateTo(range(a,130,160))};delay(200)
            onView(allOf(isAssignableFrom(WebView::class.java),isDisplayed())).perform(longClick());onView(withText("Цвет")).perform(click());onView(withText("Синий")).perform(click());delay(300)
            assertEquals(2,NoteStore(a,b.id).notes.size);assertTrue(NoteStore(a,b.id).notes.any{it.optString("color")=="blue"&&it.optString("comment").isEmpty()})
            onView(withText("Меню")).perform(click());onView(withText("Пометки · 2")).perform(click())
            onView(withContentDescription("Символов контекста с каждой стороны")).perform(replaceText("123"));androidx.test.espresso.Espresso.closeSoftKeyboard();onView(withText("Применить")).perform(click());assertEquals(123,NoteStore(a,b.id).contextN)
            onView(withText("Контексты")).perform(click());onView(withText("Пометки")).perform(click());onView(withTagValue(equalTo("delete-old-p0"))).perform(click());onView(withText("Отменить удаление")).perform(click());assertEquals(2,NoteStore(a,b.id).notes.size)
            onView(withText("К чтению")).perform(click());ready(s)
        }
    }
    @Test fun backwardTapPresentationTraceRevealsOnlyAfterAckAndVisualState()=runBlocking {
        launch().use{s->seed(s);var a=ready(s)
            withContext(Dispatchers.Main){a.readerSessionForTests()!!.navigateBoundary(1,BoundaryPlacement.RESOURCE_START,ReadingTrace.Source.NATIVE)};a=ready(s,1)
            val frames=java.util.concurrent.CopyOnWriteArrayList<Int>()
            val readerView=(a.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment).requireView()
            fun web(v:android.view.View):WebView?{if(v is WebView&&v.isShown&&v.getGlobalVisibleRect(android.graphics.Rect())&&android.net.Uri.parse(v.url ?: "").path?.endsWith("/r0.xhtml")==true)return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount)web(v.getChildAt(i))?.let{return it};return null}
            val listener=android.view.ViewTreeObserver.OnDrawListener{if(a.readerSessionForTests()?.chapterIndex()==0&&!a.readerCoveredForTests())web(readerView)?.let{frames+=it.scrollY}}
            withContext(Dispatchers.Main){readerView.viewTreeObserver.addOnDrawListener(listener)}
            val point=CoordinatesProvider{v->val p=IntArray(2);v.getLocationOnScreen(p);floatArrayOf(p[0]+v.width*.16f,p[1]+v.height*.55f)}
            onView(allOf(isAssignableFrom(WebView::class.java),isDisplayed())).perform(GeneralClickAction(Tap.SINGLE,point,Press.FINGER));a=ready(s,0)
            withTimeout(5000){while(frames.isEmpty())delay(16)}
            withContext(Dispatchers.Main){readerView.viewTreeObserver.removeOnDrawListener(listener)}
            assertTrue("First uncovered resource frame must not show its beginning",frames.first()>0)
            val trace=withContext(Dispatchers.Main){a.readingTraceForTests().getJSONArray("events")}
            val events=(0 until trace.length()).map{trace.getJSONObject(it).getString("event")}
            val hide=events.lastIndexOf("PRESENT_HIDE");val ack=events.lastIndexOf("ACK");val visual=events.lastIndexOf("PRESENT_VISUAL_READY");val reveal=events.lastIndexOf("PRESENT_REVEAL")
            assertTrue(hide>=0&&hide<ack&&ack<visual&&visual<reveal);assertFalse(withContext(Dispatchers.Main){a.readerCoveredForTests()})
            val nav=a.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
            val state=withContext(Dispatchers.Main){LocatorBridge(a).call(nav,"JJ.contentState()")!!};assertTrue(state.getDouble("scrollY")>0);assertEquals(minOf(4*state.getDouble("lineHeight"),state.getDouble("viewportHeight")/2),state.getDouble("freeBelow"),2.0)
        }
    }
}
