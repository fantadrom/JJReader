package com.fantadrom.jjreader.books

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import com.fantadrom.jjreader.text.TextModel
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile

data class Chapter(val href: String, val title: String, val text: String)
data class Book(val id: String, val title: String, val file: File, val chapters: List<Chapter>) {
    val total = chapters.sumOf { TextModel.length(it.text) }
    fun offset(index: Int) = chapters.take(index).sumOf { TextModel.length(it.text) }
}

class BookStore(private val context: Context) {
    private val root = File(context.filesDir, "books-v1").apply { mkdirs() }
    fun stage(uri: Uri) = stage(context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл"))
    fun stage(input: InputStream): File {
        val stage = File(root, ".import-${UUID.randomUUID()}.epub")
        try {
            input.use { source -> stage.outputStream().use { target ->
                val buffer = ByteArray(65536)
                var count = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    count += n
                    require(count <= 150L * 1024 * 1024) { "P0: EPUB больше 150 МБ" }
                    target.write(buffer, 0, n)
                }
                target.fd.sync()
            } }
            validateZip(stage)
            return stage
        } catch (e: Exception) { stage.delete(); throw e }
    }
    fun commit(stage: File, title: String, chapters: List<Chapter>): Book {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { check(mkdir()) }
        try {
            val file = File(dir, "book.epub")
            check(stage.renameTo(file))
            val meta = JSONObject().put("schema", 1).put("id", id).put("title", title)
                .put("sha256", TextModel.hash(file.readBytes()))
                .put("chapters", JSONArray(chapters.map { JSONObject().put("href", it.href).put("title", it.title).put("text", it.text) }))
            writeAtomic(File(dir, "book.json"), meta)
            writeAtomic(File(root, "current.json"), JSONObject().put("schema", 1).put("id", id))
            return Book(id, title, file, chapters)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
    fun current(): Book? {
        val current = File(root, "current.json")
        if (!current.exists()) return null
        val id = readAtomic(current).getString("id")
        require(UUID.fromString(id).toString() == id)
        val dir = File(root, id)
        val meta = readAtomic(File(dir, "book.json"))
        require(meta.getInt("schema") == 1) { "Неизвестная версия данных книги" }
        val chapters = meta.getJSONArray("chapters")
        return Book(id, meta.getString("title"), File(dir, "book.epub").also { require(it.exists()) { "Копия книги недоступна" } },
            (0 until chapters.length()).map { chapters.getJSONObject(it).run { Chapter(getString("href"), getString("title"), getString("text")) } })
    }
    companion object {
        fun validateZip(file: File) {
            ZipFile(file).use { zip ->
                var total = 0L
                require(zip.size() <= 10000) { "Слишком много файлов в EPUB" }
                zip.entries().asSequence().forEach {
                    val path = it.name.replace('\\', '/')
                    require(!path.startsWith('/') && !path.contains(':') && path.split('/').none { p -> p == ".." }) { "Небезопасный путь в EPUB" }
                    require(it.size in 0..32L * 1024 * 1024) { "Слишком большой ресурс EPUB" }
                    total += it.size
                    require(total <= 300L * 1024 * 1024) { "Слишком большой распакованный EPUB" }
                }
                require(zip.getEntry("META-INF/container.xml") != null) { "Это не EPUB" }
                require(zip.getEntry("META-INF/encryption.xml") == null && zip.getEntry("META-INF/license.lcpl") == null) { "P0 не открывает DRM/зашифрованные EPUB, включая обфусцированные шрифты" }
            }
        }
        fun writeAtomic(file: File, value: JSONObject) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(value.toString().toByteArray()); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
        fun readAtomic(file: File): JSONObject = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    }
}
