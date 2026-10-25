package com.fantadrom.jjreader.notes

import android.content.Context
import com.fantadrom.jjreader.books.BookStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One versioned, atomic document per internal book ID. No destructive migration. */
class NoteStore(context: Context, bookId: String) {
    private val file = File(context.filesDir, "books-v1/$bookId/reader.json")
    val data = if (file.exists()) BookStore.readAtomic(file).also {
        require(it.getInt("schema") == 1) { "Неизвестная версия пометок" }
    } else JSONObject().put("schema", 1).put("notes", JSONArray()).put("fontSize", 1.0)
    init {
        // In Readium 3.4.0 Length.Percent(1.0).toCss() == 100%; existing P0 values
        // already use these units. Version the unit explicitly without multiplying by 100.
        if (!data.has("fontUnits")) data.put("fontUnits", "readium-scale-v1")
    }
    val notes: List<JSONObject> get() = data.getJSONArray("notes").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun save() = BookStore.writeAtomic(file, data)
    fun add(note: JSONObject) { data.getJSONArray("notes").put(note); data.remove("draft"); save() }
}
