@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.content.ContextWrapper
import org.robolectric.RuntimeEnvironment
import com.fantadrom.jjreader.books.BookStore
import com.fantadrom.jjreader.notes.NoteStore
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.UserProperties
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReaderPreferencesTest {
    @Test fun pinnedReadiumFontUnitIsRatioNotIntegerPercent() {
        assertEquals("100%", Length.Percent(1.0).toCss())
        assertTrue(UserProperties(fontSize = Length.Percent(1.45)).toCss()!!.contains("145%"))
    }
    @Test fun explicitFontUnitMigrationRetainsLegacyScaleNotesDraftAndLocator() {
        val root = Files.createTempDirectory("reader-preferences").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = root }
        val folder = File(root, "books-v1/test").apply { mkdirs() }
        val old = JSONObject().put("schema", 1).put("fontSize", 1.45).put("notes", JSONArray().put(JSONObject().put("id", "original")))
            .put("draft", JSONObject().put("comment", "Черновик")).put("locator", JSONObject().put("href", "EPUB/c1.xhtml"))
        try {
            BookStore.writeAtomic(File(folder, "reader.json"), old)
            NoteStore(context, "test").save()
            val updated = NoteStore(context, "test").data
            assertEquals(1.45, updated.getDouble("fontSize"), 0.0)
            assertEquals("readium-scale-v1", updated.getString("fontUnits"))
            val promoted=JSONObject(updated.getJSONArray("notes").getJSONObject(0).toString())
            assertEquals(2,promoted.remove("noteVersion"));assertEquals("yellow",promoted.remove("color"))
            assertEquals(old.getJSONArray("notes").toString(),JSONArray().put(promoted).toString())
            val draft=JSONObject(updated.getJSONObject("draft").toString());assertEquals(2,draft.remove("noteVersion"));assertEquals("yellow",draft.remove("color"))
            assertEquals(old.getJSONObject("draft").toString(),draft.toString())
            assertEquals(old.getJSONObject("locator").toString(), updated.getJSONObject("locator").toString())
        } finally { root.deleteRecursively() }
    }
}
