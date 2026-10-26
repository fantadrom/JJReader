package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.ReadingMode
import com.fantadrom.jjreader.notes.NoteStore
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LibraryCopiesTest {
    @Test fun oldOrphanCopiesNotesHashAndSelectionAreNonDestructive()=kotlinx.coroutines.runBlocking {
        val context=RuntimeEnvironment.getApplication();val store=BookStore(context)
        val source=File("src/test/resources/unified/small.epub").absoluteFile
        val engine=com.fantadrom.jjreader.engine.ReadiumEngine(context);val pub=engine.open(source)
        val chapters=try{engine.chapters(pub)}finally{pub.close()}
        val a=store.commit(store.stage(source.inputStream()),"Одинаковое название",chapters)
        val b=store.commit(store.stage(source.inputStream()),"Одинаковое название",chapters)
        val changed=source.readBytes()+byteArrayOf(0) // ZIP trailing byte, different original SHA.
        val c=store.commit(store.stage(changed.inputStream()),"Другая книга",chapters)
        val corruptId=UUID.randomUUID().toString();val corrupt=File(context.filesDir,"books-v1/$corruptId").apply{mkdirs();resolve("book.json").writeText("broken")}
        try {
            val data=NoteStore(context,a.id)
            fun anchor(start:Int)=JSONObject().put("id","old-$start").put("bookId",a.id).put("chapter",0).put("start",start).put("end",start+5)
                .put("quote",com.fantadrom.jjreader.text.TextModel.slice(chapters[0].text,start,start+5)).put("textHash",com.fantadrom.jjreader.text.TextModel.hash(chapters[0].text.toByteArray()))
                .put("comment","Старый комментарий").put("locator",JSONObject().put("href",chapters[0].href).put("type","application/xhtml+xml"))
            data.data.getJSONArray("notes").put(anchor(0))
            data.data.put("positionV2",anchor(1)).put("draft",anchor(2).put("comment","Черновик")).put("fontSize",1.45);data.save()
            store.select(b.id)
            val reader=File(context.filesDir,"books-v1/${a.id}/reader.json");val before=reader.readBytes()
            // Emulate a pre-hash record and an incorrect saved hash; neither can hide old copies.
            for(book in listOf(a,b)){val f=book.file.parentFile!!.resolve("book.json");val meta=BookStore.readAtomic(f);meta.remove("sha256");BookStore.writeAtomic(f,meta)}
            val count=store.entries().size
            val cancelledImport=store.commit(store.stage(source.inputStream()),"Cancelled new copy",chapters)
            store.discardUnopenedImport(cancelledImport);assertEquals(count,store.entries().size)
            store.discardUnopenedImport(a);assertTrue(a.file.exists()) // Old note-bearing data is never cleanup material.
            repeat(3){
                val entries=store.entries();assertTrue(entries.any{it.id==a.id && it.notes==1});assertTrue(entries.any{it.id==b.id && it.notes==0});assertFalse(entries.single{it.id==corruptId}.available)
                val renamed=File(context.cacheDir,"different-name.epub");source.copyTo(renamed,overwrite=true)
                val differentName=store.stage(android.net.Uri.fromFile(renamed));try{assertEquals(setOf(a.id,b.id),store.identical(differentName).map{it.id}.toSet())}finally{differentName.delete();renamed.delete()}
                store.select(a.id);assertEquals(a.id,store.current()!!.id);store.select(c.id);store.select(a.id)
                assertArrayEquals(before,reader.readBytes());assertEquals(count,store.entries().size)
                assertFalse(File(context.filesDir,"books-v1/${b.id}/reader.json").exists())
            }
            assertEquals(setOf(c.id),store.identical(c.file).map{it.id}.toSet())
            val reopened=NoteStore(context,a.id);assertEquals("Старый комментарий",reopened.notes.single().getString("comment"));assertTrue(reopened.data.has("draft"))
        }finally{for(book in listOf(a,b,c))book.file.parentFile!!.deleteRecursively();corrupt.deleteRecursively();File(context.filesDir,"books-v1/current.json").delete()};Unit
    }
    @Test fun modeMigrationRunsOnceAndExplicitComparisonSurvivesRestart() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(),0)
        prefs.edit().putBoolean("unified",true).commit()
        assertEquals("sections",ReadingMode.current(prefs));assertEquals(1,prefs.getInt("modeSchema",0))
        ReadingMode.set(prefs,"unified");assertEquals("unified",ReadingMode.current(prefs))
        ReadingMode.set(prefs,"sections");assertEquals("sections",ReadingMode.current(prefs))
    }
}
