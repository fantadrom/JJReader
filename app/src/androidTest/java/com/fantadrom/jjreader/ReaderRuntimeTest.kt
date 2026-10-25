@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import com.fantadrom.jjreader.books.BookStore
import com.fantadrom.jjreader.engine.LocatorBridge
import com.fantadrom.jjreader.engine.ReaderDecorations
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.regression.ReaderRuntimeActivity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderRuntimeTest {
    private fun launch(token: String): ActivityScenario<ReaderRuntimeActivity> = ActivityScenario.launch(
        Intent(InstrumentationRegistry.getInstrumentation().targetContext, ReaderRuntimeActivity::class.java).putExtra("testToken", token))
    private fun activity(scenario: ActivityScenario<ReaderRuntimeActivity>): ReaderRuntimeActivity {
        lateinit var result: ReaderRuntimeActivity; scenario.onActivity { result = it }; return result
    }
    private suspend fun ready(scenario: ActivityScenario<ReaderRuntimeActivity>): ReaderRuntimeActivity = withTimeout(15000) {
        while (true) {
            val a = activity(scenario)
            if (withContext(Dispatchers.Main) { a.readerSessionForTests()?.ready == true }) return@withTimeout a
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("READER_NOT_READY")
    }
    private fun nav(a: ReaderRuntimeActivity) = a.supportFragmentManager.findFragmentByTag("reader") as EpubNavigatorFragment
    private fun state(a: ReaderRuntimeActivity) = NoteStore(a, checkNotNull(BookStore(a).current()).id).data
    @Test fun actualUiFontActionModeNotesRecreationAndVisibleUnderline() = runBlocking {
        launch(UUID.randomUUID().toString()).use { scenario ->
            onView(withText("Пример P0")).perform(click()); var a = ready(scenario)
            val before = withContext(Dispatchers.Main) { LocatorBridge(a).call(nav(a), "JJ.context()")!!.getDouble("fontSize") }
            repeat(3) { onView(withText("А+")).perform(click()) }
            withTimeout(15000) { while (withContext(Dispatchers.Main) { LocatorBridge(a).call(nav(a), "JJ.context()")!!.getDouble("fontSize") } <= before * 1.2) delay(100) }
            onView(withText("А−")).perform(click()); a = ready(scenario)
            for ((paragraph, comment) in listOf("p1" to "первое", "p5" to "второе")) {
                a = ready(scenario)
                longPressWord(a, paragraph)
                onView(withText("Пометка")).perform(click())
                onView(withHint("Короткий комментарий")).perform(replaceText(comment), closeSoftKeyboard())
                onView(withText("Сохранить")).perform(click())
            }
            val notes = withContext(Dispatchers.Main) { state(a).getJSONArray("notes") }
            assertEquals(2, notes.length()); assertNotEquals(notes.getJSONObject(0).getInt("start"), notes.getJSONObject(1).getInt("start"))
            a = ready(scenario)
            longPressWord(a, "p1"); onView(withText("Пометка")).perform(click())
            onView(withHint("Короткий комментарий")).perform(replaceText("черновик"), closeSoftKeyboard())
            scenario.recreate()
            withTimeout(15000) { while (!withContext(Dispatchers.Main) { activity(scenario).commentEditorVisibleForTests() }) delay(100) }
            onView(withHint("Короткий комментарий")).check(matches(withText("черновик")))
            onView(withText("Отменить")).perform(click()); a = ready(scenario)
            val position = scrollAndSave(a)
            scenario.recreate(); a = ready(scenario)
            assertNearSavedText(a, position)
            assertEquals(2, withContext(Dispatchers.Main) { state(a).getJSONArray("notes").length() })
            val previousActivity = a
            scenario.onActivity { it.requestedOrientation = if (it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            withTimeout(15000) { while (activity(scenario) === previousActivity) delay(100) }
            a = ready(scenario); assertNearSavedText(a, position)
            withContext(Dispatchers.Main) {
                val bridge = LocatorBridge(a); val anchor = bridge.call(nav(a), "JJ.anchor(1500,1570)")!!
                nav(a).applyDecorations(listOf(Decoration("test-tts", bridge.locator(nav(a).currentLocator.value, anchor), Decoration.Style.Underline(ReaderDecorations.BLUE))), "tts")
            }
            withTimeout(10000) {
                while (!withContext(Dispatchers.Main) { LocatorBridge(a).call(nav(a), "({visible:Array.from(document.querySelectorAll('[id^=\"r2-decoration-\"] *')).some(e=>parseFloat(getComputedStyle(e).borderBottomWidth)>=3 && e.getBoundingClientRect().width>0 && getComputedStyle(e).borderBottomColor==='rgb(34, 103, 208)')})")!!.getBoolean("visible") }) delay(100)
            }
            withContext(Dispatchers.Main) { nav(a).applyDecorations(emptyList(), "tts") }
        }
    }
    private suspend fun longPressWord(a: ReaderRuntimeActivity, paragraph: String) {
        // JS computes coordinates only. Selection is created by a genuine Android long-press.
        val point = withContext(Dispatchers.Main) {
            LocatorBridge(a).call(nav(a), """(function(){const n=document.getElementById('$paragraph').firstChild;const i=n.data.indexOf('маяк');
                const r=document.createRange();r.setStart(n,i);r.setEnd(n,i+4);window.scrollBy(0,r.getBoundingClientRect().top-100);
                const b=r.getBoundingClientRect();return {x:b.left+b.width/2,y:b.top+b.height/2};})()""")!!
        }
        val screen = IntArray(2)
        val density = withContext(Dispatchers.Main) { nav(a).publicationView.getLocationOnScreen(screen); a.resources.displayMetrics.density }
        val x = screen[0] + point.getDouble("x").toFloat() * density; val y = screen[1] + point.getDouble("y").toFloat() * density
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val began = SystemClock.uptimeMillis()
        MotionEvent.obtain(began, began, MotionEvent.ACTION_DOWN, x, y, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
        delay(800)
        MotionEvent.obtain(began, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).also { instrumentation.sendPointerSync(it); it.recycle() }
        instrumentation.waitForIdleSync()
    }
    private suspend fun scrollAndSave(a: ReaderRuntimeActivity): JSONObject {
        withContext(Dispatchers.Main) { LocatorBridge(a).call(nav(a), "({ok:JJ.requestRestore('test-middle',1500,1504)})") }
        return withTimeout(12000) {
            while (true) {
                val position = withContext(Dispatchers.Main) { state(a).optJSONObject("positionV2") }
                if (position != null && position.getInt("start") > 1200) return@withTimeout JSONObject(position.toString())
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("POSITION_NOT_SAVED")
        }
    }
    private suspend fun assertNearSavedText(a: ReaderRuntimeActivity, expected: JSONObject) = withContext(Dispatchers.Main) {
        val viewport = LocatorBridge(a).call(nav(a), "JJ.viewport()")!!
        assertTrue(kotlin.math.abs(viewport.getInt("first") - expected.getInt("start")) < 150)
        assertTrue(viewport.getInt("first") > 1000)
    }
    @Test fun processRestartProbe() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("p02ProcessPhase")
        assumeTrue("Run paired phases with tools/android_process_restart.ps1", phase in listOf("prepare", "verify"))
        launch("process-p02").use { scenario ->
            if (phase == "prepare") {
                onView(withText("Пример P0")).perform(click()); val a = ready(scenario)
                BookStore.writeAtomic(File(a.filesDir, "process-expected.json"), scrollAndSave(a).put("pid", Process.myPid()))
            } else {
                val a = ready(scenario); val expected = BookStore.readAtomic(File(a.filesDir, "process-expected.json"))
                assertNotEquals("A new OS process is required", expected.getInt("pid"), Process.myPid())
                assertNearSavedText(a, expected)
            }
        }
    }
}
