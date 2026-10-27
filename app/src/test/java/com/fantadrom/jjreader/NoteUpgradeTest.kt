package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.notes.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NoteUpgradeTest {
    private fun setup():Pair<String,File>{val id=UUID.randomUUID().toString();return id to File(RuntimeEnvironment.getApplication().filesDir,"books-v1/$id").apply{mkdirs()}}
    @Test fun trialNotesAndDraftUpgradeAdditivelyThenEditDoesNotDuplicateOrMoveBinding(){
        val (id,dir)=setup();try{
            val old=JSONObject().put("id","old").put("bookId",id).put("chapter",1).put("start",12).put("end",17).put("quote","цитата").put("comment","Первый комментарий").put("locator",JSONObject().put("href","r1.xhtml")).put("custom","kept")
            val before=JSONObject(old.toString());BookStore.writeAtomic(File(dir,"reader.json"),JSONObject().put("schema",1).put("notes",JSONArray().put(old)).put("draft",JSONObject(old.toString()).put("id","draft")).put("positionV2",JSONObject().put("start",90)).put("fontSize",1.4))
            val store=NoteStore(RuntimeEnvironment.getApplication(),id);val upgraded=store.notes.single();before.keys().forEach{assertEquals(before.get(it).toString(),upgraded.get(it).toString())};assertEquals("yellow",upgraded.getString("color"));assertEquals("draft",store.data.getJSONObject("draft").getString("id"))
            store.draft(JSONObject(upgraded.toString()).put("comment","Изменено").put("color","blue"),"old");store.commitDraft();assertEquals(1,store.notes.size)
            val reopened=NoteStore(RuntimeEnvironment.getApplication(),id);val note=reopened.notes.single();assertEquals("old",note.getString("id"));assertEquals("Изменено",note.getString("comment"));assertEquals("blue",note.getString("color"));assertEquals(before.getJSONObject("locator").toString(),note.getJSONObject("locator").toString());assertEquals(12,note.getInt("start"));assertEquals(90,reopened.data.getJSONObject("positionV2").getInt("start"));assertEquals(1.4,reopened.data.getDouble("fontSize"),.001)
        }finally{dir.deleteRecursively()}
    }
    @Test fun colorOnlyRemoveColorWithCommentUndoAndDraftSurviveReload(){
        val (id,dir)=setup();try{
            var store=NoteStore(RuntimeEnvironment.getApplication(),id)
            val n=JSONObject().put("id","new").put("quote","слово").put("comment","").put("color","purple").put("noteVersion",2)
            store.draft(n);store.commitDraft();assertEquals("purple",store.notes.single().getString("color"))
            store.draft(JSONObject(store.notes.single().toString()).put("comment","Добавлен текст").put("color",null),"new");store.commitDraft();assertFalse(store.notes.single().has("color"))
            store.setContextN(123);store.delete("new");assertTrue(store.notes.isEmpty());store=NoteStore(RuntimeEnvironment.getApplication(),id);assertTrue(store.undoDelete());assertEquals("Добавлен текст",store.notes.single().getString("comment"));assertEquals(123,store.contextN)
            store.draft(JSONObject(store.notes.single().toString()).put("comment","Черновик изменения"),"new");store=NoteStore(RuntimeEnvironment.getApplication(),id);assertEquals("Черновик изменения",store.data.getJSONObject("draft").getString("comment"));assertEquals("Добавлен текст",store.notes.single().getString("comment"));store.discardDraft();assertEquals(1,store.notes.size)
            store.draft(JSONObject().put("id","empty").put("comment","").put("noteVersion",2));try{store.commitDraft();fail("Empty draft")}catch(_:IllegalArgumentException){};assertEquals(1,store.notes.size)
        }finally{dir.deleteRecursively()}
    }
}
