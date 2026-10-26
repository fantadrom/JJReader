package com.fantadrom.jjreader.books

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.JsonReader
import java.security.MessageDigest
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

data class BookEntry(val id:String,val title:String,val notes:Int,val current:Boolean,val available:Boolean)

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
                .put("sha256", sha256(file))
                .put("chapters", JSONArray(chapters.map { JSONObject().put("href", it.href).put("title", it.title).put("text", it.text) }))
            writeAtomic(File(dir, "book.json"), meta)

            return Book(id, title, file, chapters)
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
    internal fun discardUnopenedImport(book:Book) {
        val dir=File(root,book.id)
        require(book.file.canonicalFile==File(dir,"book.epub").canonicalFile)
        val current=runCatching{readAtomic(File(root,"current.json")).getString("id")}.getOrNull()
        if(current!=book.id && !File(dir,"reader.json").exists() && !File(dir,"reader.json.bak").exists())dir.deleteRecursively()
    }
    fun current(): Book? {
        val current = File(root, "current.json")
        if (!current.exists()) return null
        val id = readAtomic(current).getString("id")
        require(UUID.fromString(id).toString() == id)
        return open(id)
    }
    fun select(id:String) { require(UUID.fromString(id).toString()==id);check(File(root,"$id/book.epub").isFile && File(root,"$id/book.json").isFile);writeAtomic(File(root,"current.json"),JSONObject().put("schema",1).put("id",id)) }
    fun open(id:String):Book {
        require(UUID.fromString(id).toString()==id)
        val dir = File(root, id)
        val meta = readAtomic(File(dir, "book.json"))
        require(meta.getInt("schema") == 1) { "Неизвестная версия данных книги" }
        val chapters = meta.getJSONArray("chapters")
        return Book(id, meta.getString("title"), File(dir, "book.epub").also { require(it.exists()) { "Копия книги недоступна" } },
            (0 until chapters.length()).map { chapters.getJSONObject(it).run { Chapter(getString("href"), getString("title"), getString("text")) } })
    }
    /** Streaming metadata only: never constructs all chapter texts or a NoteStore. */
    fun entries():List<BookEntry> {
        val current=runCatching{readAtomic(File(root,"current.json")).getString("id")}.getOrNull()
        return root.listFiles().orEmpty().filter{it.isDirectory && runCatching{UUID.fromString(it.name).toString()==it.name && it.canonicalFile.parentFile==root.canonicalFile}.getOrDefault(false)}.map{dir->
            var title="Недоступная копия";var count=0
            val valid=runCatching {
                var schema=0;var titleFound=false;var chaptersFound=false
                JsonReader(AtomicFile(File(dir,"book.json")).openRead().reader()).use{r->r.beginObject();while(r.hasNext())when(r.nextName()){
                    "title"->{check(r.peek()==android.util.JsonToken.STRING);title=r.nextString();titleFound=true};"schema"->schema=r.nextInt();"chapters"->{check(r.peek()==android.util.JsonToken.BEGIN_ARRAY);r.skipValue();chaptersFound=true};else->r.skipValue()
                };r.endObject()};check(schema==1 && titleFound && chaptersFound);check(File(dir,"book.epub").isFile)
                val reader=File(dir,"reader.json")
                if(reader.exists() || File(dir,"reader.json.bak").exists())JsonReader(AtomicFile(reader).openRead().reader()).use{r->
                    var readerSchema=0;r.beginObject();while(r.hasNext())when(r.nextName()){ "schema"->readerSchema=r.nextInt();"notes"->{r.beginArray();while(r.hasNext()){r.skipValue();count++};r.endArray()};else->r.skipValue()};r.endObject();check(readerSchema==1)
                }
            }.isSuccess
            BookEntry(dir.name,title,count,dir.name==current,valid)
        }.sortedWith(compareByDescending<BookEntry>{it.current}.thenBy{it.title}.thenBy{it.id})
    }
    /** Verify original bytes even for old records with stale/missing metadata hash. No data migration. */
    fun identical(stage:File):List<BookEntry> {
        val hash=sha256(stage)
        return entries().filter{it.available && runCatching{val file=File(root,"${it.id}/book.epub");validateZip(file);sha256(file)==hash}.getOrDefault(false)}
    }
    companion object {
        fun sha256(file:File):String {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            return digest.digest().joinToString(""){"%02x".format(it)}
        }
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
