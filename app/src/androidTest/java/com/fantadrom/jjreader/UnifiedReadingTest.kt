@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import com.fantadrom.jjreader.books.BookStore
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.regression.ReaderRuntimeActivity
import com.fantadrom.jjreader.text.TextModel
import com.fantadrom.jjreader.tts.Phrase
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.util.UUID

/** Real stable navigator, persisted P0.6-form data and real vertical gestures. NOT a fake ReaderPort. */
@RunWith(AndroidJUnit4::class)
class UnifiedReadingTest {
    private fun launch()=ActivityScenario.launch<ReaderRuntimeActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext,ReaderRuntimeActivity::class.java)
        .putExtra("testToken",UUID.randomUUID().toString()).putExtra("unified",false))
    private fun activity(s:ActivityScenario<ReaderRuntimeActivity>):ReaderRuntimeActivity {lateinit var a:ReaderRuntimeActivity;s.onActivity{a=it};return a}
    private suspend fun ready(s:ActivityScenario<ReaderRuntimeActivity>,old:ReaderSession?=null):ReaderRuntimeActivity=withTimeout(30000){
        while(true){val a=activity(s);if(withContext(Dispatchers.Main){a.readerSessionForTests()?.let{it.ready && it!==old}==true})return@withTimeout a;delay(50)}
        @Suppress("UNREACHABLE_CODE")error("NOT_READY")
    }
    private fun nav(a:ReaderRuntimeActivity)=a.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
    private fun notes(a:ReaderRuntimeActivity)=NoteStore(a,checkNotNull(BookStore(a).current()).id)
    private suspend fun mode(s:ActivityScenario<ReaderRuntimeActivity>,name:String):ReaderRuntimeActivity {
        val old=withContext(Dispatchers.Main){activity(s).readerSessionForTests()}
        onView(withText("Диагностика TTS")).perform(click());onView(withText("Проверки")).perform(click())
        onView(withText("Режим: единая лента / прежний")).perform(click());onView(withText(name)).perform(click())
        return ready(s,old)
    }
    @Test fun legacyDataContinuousGesturesAnchorsEightReflowsAndLegacyReserve()=runBlocking {
        launch().use { scenario ->
            onView(withText("Пример P0")).perform(click());var a=ready(scenario)
            // Seed exactly the prior schema from actual source DOM anchors; keep IDs/comments unchanged.
            withContext(Dispatchers.Main){
                val reader=a.readerSessionForTests()!!;val store=notes(a)
                for(ch in listOf(0,1)){
                    reader.freeze();val loc=org.readium.r2.shared.publication.Locator.fromJSON(JSONObject().put("href",reader.book.chapters[ch].href).put("type","application/xhtml+xml"))!!
                    nav(a).go(loc,animated=false);reader.context(ch)
                    val anchor=LocatorBridge(a).call(nav(a),"JJ.anchor(40,50)")!!.put("chapter",ch).put("bookId",reader.book.id)
                        .put("textHash",TextModel.hash(reader.book.chapters[ch].text.toByteArray())).put("id","old-$ch").put("comment","old comment $ch")
                    anchor.put("locator",LocatorBridge(a).locator(nav(a).currentLocator.value,anchor).toJSON());store.add(anchor)
                    store.data.put("positionV2",JSONObject(anchor.toString())).put("locator",anchor.getJSONObject("locator"));store.save()
                }
            }
            scenario.recreate();a=ready(scenario)
            val oldNotes=withContext(Dispatchers.Main){notes(a).data.getJSONArray("notes").toString()}
            a=mode(scenario,"Единая лента")
            withContext(Dispatchers.Main){
                assertEquals("unified.xhtml",nav(a).currentLocator.value.href.toString())
                assertEquals(oldNotes,notes(a).data.getJSONArray("notes").toString())
                val bridge=LocatorBridge(a)
                for(ch in 0..2)assertEquals(a.readerSessionForTests()!!.book.chapters[ch].text,bridge.call(nav(a),"({text:JJ.forSection($ch).text})")!!.getString("text"))
                val length=TextModel.length(a.readerSessionForTests()!!.book.chapters[0].text)
                bridge.call(nav(a),"({ok:JJ.forSection(0).go(${length-45},${length-44})})")
            }
            delay(100)
            val document=withContext(Dispatchers.Main){val v=LocatorBridge(a).call(nav(a),"JJ.viewport()")!!;assertTrue(v.getInt("lastSourceChapter")>=1);v.getString("documentId")}
            onView(withId(23001)).perform(swipeUp());onView(withId(23001)).perform(swipeDown())
            withContext(Dispatchers.Main){assertEquals(document,LocatorBridge(a).call(nav(a),"JJ.context()")!!.getString("documentId"))}
            // A saved old source anchor is rendered afresh; the old DOM path is never applied to the unified document.
            withContext(Dispatchers.Main){a.readerSessionForTests()!!.navigateTo(notes(a).notes[1])}
            repeat(8){
                val old=activity(scenario);scenario.onActivity{it.requestedOrientation=if(it.resources.configuration.orientation==Configuration.ORIENTATION_PORTRAIT)ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
                withTimeout(15000){while(activity(scenario)===old)delay(50)};a=ready(scenario)
                withContext(Dispatchers.Main){assertEquals(1,notes(a).data.getJSONObject("positionV2").getInt("chapter"));a.readerSessionForTests()!!.context(1)}
            }
            onView(withText("А+")).perform(click());delay(500);a=ready(scenario)
            withContext(Dispatchers.Main){
                val book=a.readerSessionForTests()!!.book;val start=TextModel.length(book.chapters[0].text)-12
                a.highlightForTests(Phrase(start,start+5,TextModel.slice(book.chapters[0].text,start,start+5),0))
                a.highlightForTests(Phrase(0,5,TextModel.slice(book.chapters[1].text,0,5),1))
                val visible=LocatorBridge(a).call(nav(a),"JJ.viewport()")!!;assertEquals("unified.xhtml",nav(a).currentLocator.value.href.toString());assertTrue(visible.getInt("lastSourceChapter")>=1)
                a.highlightForTests(null)
            }
            a=mode(scenario,"Прежний режим по разделам")
            withContext(Dispatchers.Main){assertEquals(oldNotes,notes(a).data.getJSONArray("notes").toString());assertFalse(nav(a).currentLocator.value.href.toString().contains("unified"))}
        }
    }
    @Test fun unifiedProcessRestartProbe()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("p07ProcessPhase")
        org.junit.Assume.assumeTrue("Use android_process_restart.ps1 -Unified",phase in listOf("prepare","verify"))
        ActivityScenario.launch<ReaderRuntimeActivity>(Intent(InstrumentationRegistry.getInstrumentation().targetContext,ReaderRuntimeActivity::class.java).putExtra("testToken","unified-process").putExtra("unified",true)).use{scenario->
            if(phase=="prepare"){
                onView(withText("Пример P0")).perform(click());val a=ready(scenario)
                withContext(Dispatchers.Main){
                    val anchor=LocatorBridge(a).call(nav(a),"JJ.forSection(1).anchor(10,20)")!!.put("chapter",1)
                    a.readerSessionForTests()!!.navigateTo(anchor)
                    BookStore.writeAtomic(java.io.File(a.filesDir,"unified-process.json"),JSONObject().put("pid",android.os.Process.myPid()).put("chapter",1))
                }
            }else{
                val a=ready(scenario);val expected=BookStore.readAtomic(java.io.File(a.filesDir,"unified-process.json"))
                assertNotEquals(expected.getInt("pid"),android.os.Process.myPid())
                withContext(Dispatchers.Main){assertEquals(1,notes(a).data.getJSONObject("positionV2").getInt("chapter"));assertEquals("unified.xhtml",nav(a).currentLocator.value.href.toString());a.readerSessionForTests()!!.context(1)}
            }
        };Unit
    }

}
