@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.graphics.Rect
import android.net.Uri
import android.util.Xml
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantadrom.jjreader.engine.LocatorBridge
import com.fantadrom.jjreader.engine.ReadiumEngine
import com.fantadrom.jjreader.regression.RenderRegressionActivity
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.*
import org.json.JSONTokener
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** A real Activity, Readium resource server and Android WebView; not an import-only test. */
@RunWith(AndroidJUnit4::class)
class XhtmlRenderTest {
    @Test fun allSampleResourcesRenderAfterInjectionsAndProvideSelection() = runBlocking {
        ActivityScenario.launch(RenderRegressionActivity::class.java).use { scenario ->
            lateinit var activity: RenderRegressionActivity
            scenario.onActivity { activity = it }
            withTimeout(20000) {
                while (withContext(Dispatchers.Main) { activity.navigator == null && activity.failure == null }) delay(100)
            }
            withContext(Dispatchers.Main) { assertNull("Navigator initialization failed", activity.failure) }
            val nav = withContext(Dispatchers.Main) { checkNotNull(activity.navigator) }
            val pub = withContext(Dispatchers.Main) { checkNotNull(activity.publication) }
            val chapters = withContext(Dispatchers.IO) { ReadiumEngine(activity).chapters(pub) }
            val bridge = LocatorBridge(activity)
            val controls = listOf("Контрольное первое вхождение", "НАЧАЛО ВТОРОГО РЕСУРСА", "НАЧАЛО ТРЕТЬЕГО РЕСУРСА")
            for (index in chapters.indices) {
                if (index > 0) withContext(Dispatchers.Main) { assertTrue(nav.go(pub.readingOrder[index], animated = false)) }
                val rendered = withTimeout(20000) {
                    var result: JSONObject
                    while (true) {
                        result = withContext(Dispatchers.Main) {
                            decode(nav.evaluateJavascript("""JSON.stringify({
                                url:document.URL, mime:document.contentType,
                                errors:document.getElementsByTagNameNS('*','parsererror').length,
                                text:document.body ? document.body.textContent : '',
                                visible:document.querySelector('p') ? document.querySelector('p').getClientRects().length > 0 : false,
                                imageCount:document.images.length,
                                images:Array.from(document.images).every(i=>i.complete && i.naturalWidth>0)
                            })"""))
                        }
                        assertEquals("WebView displays XML parsererror", 0, result.getInt("errors"))
                        if (result.getString("text").contains(controls[index]) && result.getBoolean("images")) break
                        delay(100)
                    }
                    result
                }
                assertEquals("application/xhtml+xml", rendered.getString("mime"))
                assertTrue(rendered.getBoolean("visible"))
                if (index == 0) assertTrue("Sample illustration missing", rendered.getInt("imageCount") > 0)
                withContext(Dispatchers.Main) { assertEquals(chapters[index].text, bridge.text(nav)) }

                // Read the final response from the same client and URL used by this WebView.
                // This includes SafeHtml AND Readium's live CSS/JS injection, with its current settings.
                val pair = withContext(Dispatchers.Main) {
                    val web = webViews(checkNotNull(nav.view)).first { it.url == rendered.getString("url") && it.getGlobalVisibleRect(Rect()) }
                    web to web.webViewClient
                }
                val response = withContext(Dispatchers.IO) {
                    pair.second.shouldInterceptRequest(pair.first, object : WebResourceRequest {
                        override fun getUrl() = Uri.parse(rendered.getString("url"))
                        override fun isForMainFrame() = true
                        override fun isRedirect() = false
                        override fun hasGesture() = false
                        override fun getMethod() = "GET"
                        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
                    })
                }
                assertNotNull("Readium did not serve its current document", response)
                assertTrue(checkNotNull(response).mimeType.startsWith("application/xhtml+xml"))
                val finalXml = withContext(Dispatchers.IO) { response.data.use { it.readBytes().toString(Charsets.UTF_8) } }
                strictXmlAfterInjections(finalXml)
            }
            withContext(Dispatchers.Main) {
                val selected = decode(nav.evaluateJavascript("""(function(){
                    const p=document.querySelector('p'), n=p.firstChild, r=document.createRange();
                    r.setStart(n,0);r.setEnd(n,Math.min(10,n.length));
                    const s=window.getSelection();s.removeAllRanges();s.addRange(r);
                    return JSON.stringify({selected:s.toString().length>0});
                })()"""))
                assertTrue(selected.getBoolean("selected"))
                assertFalse(nav.currentSelection()?.locator?.text?.highlight.isNullOrBlank())
                val anchor = checkNotNull(bridge.call(nav, "JJ.selection()"))
                val locator = bridge.locator(nav.currentLocator.value, anchor)
                assertFalse(locator.text.highlight.isNullOrBlank())
                assertEquals(anchor.getString("quote"), TextModel.slice(chapters.last().text, anchor.getInt("start"), anchor.getInt("end")))
            }
        }
    }
    private fun decode(raw: String?): JSONObject {
        val value = checkNotNull(raw)
        return JSONObject(if (value.startsWith('"')) JSONTokener(value).nextValue() as String else value)
    }
    private fun webViews(view: View): List<WebView> = if (view is WebView) listOf(view) else if (view is ViewGroup)
        (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) } else emptyList()
    private fun strictXmlAfterInjections(xml: String) {
        assertTrue(xml.contains("readium-reflowable.js"))
        assertTrue(xml.contains("ReadiumCSS-before.css"))
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(StringReader(xml))
        }
        while (parser.next() != XmlPullParser.END_DOCUMENT) { /* Strict parse of the complete response. */ }
    }
}
