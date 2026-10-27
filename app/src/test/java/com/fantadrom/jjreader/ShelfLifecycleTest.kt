package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.ui.Appearance
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ShelfLifecycleTest {
    @Test fun clearAndDeleteOnlyChosenInternalCopyAndDerivedAudio() {
        val context=RuntimeEnvironment.getApplication();val store=BookStore(context)
        val source=File("src/test/resources/sections/p08-sections.epub").absoluteFile
        val chapters=listOf(Chapter("s0.xhtml","Глава","Текст"))
        val a=store.commit(store.stage(source.inputStream()),"Одинаково",chapters,"one.epub")
        val b=store.commit(store.stage(source.inputStream()),"Одинаково",chapters,"two.epub")
        try {
            val notes=NoteStore(context,a.id);val place=JSONObject().put("bookId",a.id).put("chapter",0).put("start",1).put("end",2).put("quote","е")
            notes.data.put("positionV2",place).put("fontSize",1.45).put("progress",42).put("draft",JSONObject().put("comment","Черновик"));notes.add(JSONObject().put("id","old").put("comment","Комментарий"));notes.data.put("draft",JSONObject().put("comment","Черновик"));notes.save()
            val other=NoteStore(context,b.id);other.data.put("positionV2",JSONObject().put("chapter",0).put("start",3)).put("progress",72);other.save()
            val otherBytes=File(context.filesDir,"books-v1/${b.id}/reader.json").readBytes();store.select(a.id)
            val list=store.entries();val entry=list.single{it.id==a.id};assertEquals("one.epub",entry.sourceName);assertEquals(42,entry.progress);assertTrue(entry.importedAt>0);assertEquals(1,entry.notes)
            store.clearNotes(a.id)
            val reopened=NoteStore(context,a.id);assertEquals(0,reopened.notes.size);assertFalse(reopened.data.has("draft"));assertEquals(place.toString(),reopened.data.getJSONObject("positionV2").toString());assertEquals(1.45,reopened.data.getDouble("fontSize"),.001);assertEquals(42,reopened.data.getInt("progress"))
            val derived=File(context.filesDir,"renditions-v1/${a.id}/cache").apply{mkdirs();resolve("sample").writeText("derived")};val audio=File(context.cacheDir,"tts-p1/${a.id}").apply{mkdirs();resolve("cached.mp3").writeText("cache")}
            assertTrue(store.delete(a.id));assertFalse(a.file.exists());assertFalse(derived.exists());assertFalse(audio.exists());assertNull(store.current());assertTrue(source.exists());assertTrue(b.file.exists());assertArrayEquals(otherBytes,File(context.filesDir,"books-v1/${b.id}/reader.json").readBytes());store.select(b.id);assertEquals(b.id,store.current()!!.id)
        }finally{store.delete(a.id);store.delete(b.id)}
    }
    @Test fun oldAtomicBackupsRecoverWithoutEmptyReplacement() {
        val context=RuntimeEnvironment.getApplication();val store=BookStore(context);val source=File("src/test/resources/sections/p08-sections.epub")
        val book=store.commit(store.stage(source.inputStream()),"Прежняя книга",listOf(Chapter("s0.xhtml","Раздел","Текст")))
        try {
            val notes=NoteStore(context,book.id);notes.add(JSONObject().put("id","old").put("comment","Сохранено"));notes.data.put("positionV2",JSONObject().put("chapter",0).put("start",2));notes.save();store.select(book.id)
            val reader=File(context.filesDir,"books-v1/${book.id}/reader.json");assertTrue(reader.renameTo(File(reader.path+".bak")))
            val current=File(context.filesDir,"books-v1/current.json");assertTrue(current.renameTo(File(current.path+".bak")))
            assertEquals(book.id,store.current()!!.id);val restored=NoteStore(context,book.id);assertEquals("Сохранено",restored.notes.single().getString("comment"));assertEquals(2,restored.data.getJSONObject("positionV2").getInt("start"))
        }finally{store.delete(book.id)}
    }
    @Test fun appearanceDoesNotChangeSystemThemeOrPermitInvisibleBrightness() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("appearance-test",0);prefs.edit().clear().commit()
        val config=android.content.res.Configuration();config.uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES
        assertTrue(Appearance.dark(prefs,config));prefs.edit().putString("theme","light").commit();assertFalse(Appearance.dark(prefs,config));prefs.edit().putString("theme","dark").commit();assertTrue(Appearance.dark(prefs,config))
        assertEquals(.5f,Appearance.brightness(Float.NaN));assertEquals(.05f,Appearance.brightness(-1f));assertEquals(1f,Appearance.brightness(10f));prefs.edit().putString("font","invalid").commit();assertEquals("serif",Appearance.font(prefs))
    }
}
