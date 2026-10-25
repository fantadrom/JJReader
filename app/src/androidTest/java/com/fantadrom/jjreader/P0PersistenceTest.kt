package com.fantadrom.jjreader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantadrom.jjreader.books.BookStore
import com.fantadrom.jjreader.engine.ReadiumEngine
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class P0PersistenceTest {
    private fun isolated(test: (Context) -> Unit) {
        val real = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(real.cacheDir, "p0-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(real) { override fun getFilesDir() = directory }
        try { test(context) } finally { directory.deleteRecursively() }
    }
    @Test fun realReadiumImportAndDurableNote() = isolated { context -> runBlocking {
        val store = BookStore(context)
        val stage = store.stage(context.assets.open("p0-sample.epub"))
        val engine = ReadiumEngine(context)
        val pub = engine.open(stage)
        val chapters = try { engine.chapters(pub) } finally { pub.close() }
        assertEquals(3, chapters.size)
        assertFalse(chapters.any { it.text.contains("Альтернативный текст") })
        val book = store.commit(stage, "Проверка", chapters)
        val note = JSONObject().put("id", "trial").put("chapter", 0).put("start", 0).put("end", 4)
            .put("quote", TextModel.slice(chapters[0].text, 0, 4)).put("comment", "Проверка сохранения")
        NoteStore(context, book.id).add(note)
        assertEquals(book.id, BookStore(context).current()!!.id)
        assertEquals(note.toString(), NoteStore(context, book.id).notes.single().toString())
        assertEquals(chapters[0].text, BookStore(context).current()!!.chapters[0].text)
    } }
    @Test fun failedStageLeavesPreviousBookAndDraftUntouched() = isolated { context -> runBlocking {
        val store = BookStore(context); val stage = store.stage(context.assets.open("p0-sample.epub"))
        val pub = ReadiumEngine(context).open(stage)
        val chapters = try { ReadiumEngine(context).chapters(pub) } finally { pub.close() }
        val book = store.commit(stage, "Книга", chapters)
        NoteStore(context, book.id).apply { data.put("draft", JSONObject().put("comment", "черновик")); save() }
        try { store.stage("Not a zip".byteInputStream()); fail("Expected rejection") } catch (_: java.util.zip.ZipException) { }
        assertEquals(book.id, store.current()!!.id)
        assertEquals("черновик", NoteStore(context, book.id).data.getJSONObject("draft").getString("comment"))
    } }
}
