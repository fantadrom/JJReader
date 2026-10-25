package com.fantadrom.jjreader.engine

import android.content.Context
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

class LocatorBridge(context: Context) {
    private val script = context.assets.open("locator-bridge.js").bufferedReader().use { it.readText() }
    suspend fun call(nav: EpubNavigatorFragment, expression: String): JSONObject? {
        val raw = nav.evaluateJavascript("$script\nJSON.stringify($expression);") ?: return null
        // WebView returns JSON-encoded JS strings; Readium versions may also return raw JSON.
        val decoded = if (raw.startsWith('"')) org.json.JSONTokener(raw).nextValue() as? String else raw
        return decoded?.takeIf { it != "null" }?.let(::JSONObject)
    }
    suspend fun text(nav: EpubNavigatorFragment) = call(nav, "({text:JJ.text})")?.getString("text")
    fun locator(base: Locator, anchor: JSONObject): Locator {
        val json = base.toJSON()
        // Progression is deliberately removed: the structural DOM range is authoritative.
        json.put("locations", anchor.getJSONObject("locations"))
        json.put("text", anchor.getJSONObject("locatorText"))
        return Locator.fromJSON(json) ?: error("Не удалось создать локатор")
    }
}
