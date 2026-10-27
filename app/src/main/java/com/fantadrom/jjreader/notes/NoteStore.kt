package com.fantadrom.jjreader.notes

import android.content.Context
import com.fantadrom.jjreader.books.BookStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic schema1 document; P2 adds optional fields without rewriting quotes or source anchors. */
class NoteStore(context:Context,bookId:String) {
    enum class Source { NOTES, ACTIVITY, SESSION }
    val instanceId=java.util.UUID.randomUUID().toString()
    var onSaved:((Source,JSONObject,JSONObject?)->Unit)?=null
    private val file=File(context.filesDir,"books-v1/$bookId/reader.json")
    private val existed=file.exists()||File(file.path+".bak").exists()
    val data=if(existed)BookStore.readAtomic(file).also{require(it.getInt("schema")==1){"Неизвестная версия пометок"}}else JSONObject().put("schema",1).put("notes",JSONArray()).put("fontSize",1.0)
    init {
        if(!data.has("fontUnits"))data.put("fontUnits","readium-scale-v1")
        var changed=false
        // Trial notes were rendered yellow. Preserve that appearance and every original field/ID.
        notes.forEach{if(!it.has("noteVersion")){it.put("noteVersion",2);if(!it.has("color"))it.put("color",NoteColor.YELLOW.key);changed=true}}
        data.optJSONObject("draft")?.let{if(!it.has("noteVersion")){it.put("noteVersion",2);if(!it.has("color"))it.put("color",NoteColor.YELLOW.key);changed=true}}
        if(existed&&changed)save()
    }
    val notes:List<JSONObject> get()=data.getJSONArray("notes").let{a->(0 until a.length()).map{a.getJSONObject(it)}}
    val contextN get()=data.optInt("contextN",350).coerceAtLeast(0)
    fun readDisk():JSONObject?=runCatching{BookStore.readAtomic(file)}.getOrNull()
    fun save()=save(Source.NOTES)
    fun save(source:Source){BookStore.writeAtomic(file,data);runCatching{onSaved?.invoke(source,data,readDisk())}}
    private fun change(block:()->Unit){val before=JSONObject(data.toString());try{block();save()}catch(e:Exception){data.keys().asSequence().toList().forEach(data::remove);before.keys().forEach{data.put(it,before.get(it))};throw e}}
    /** Retained for old regression fixtures; production uses validated commitDraft. */
    fun add(note:JSONObject)=change{data.getJSONArray("notes").put(JSONObject(note.toString()));data.remove("draft");data.remove("draftEditingId")}
    fun setContextN(n:Int){require(n>=0);change{data.put("contextN",n)}}
    fun draft(value:JSONObject,editingId:String?=null)=change{data.put("draft",JSONObject(value.toString()).put("noteVersion",2));data.put("draftEditingId",editingId)}
    fun discardDraft()=change{data.remove("draft");data.remove("draftEditingId")}
    fun commitDraft():JSONObject {
        val draft=JSONObject(data.getJSONObject("draft").toString());require(draft.optString("comment").isNotBlank()||NoteColor.of(draft.optString("color"))!=null){"Добавьте комментарий или цвет"}
        val id=draft.getString("id");val editing=data.optString("draftEditingId","")
        val index=notes.indexOfFirst{it.optString("id")==id};check(editing.isEmpty()||editing==id&&index>=0){"Пометка уже недоступна"}
        change{val all=data.getJSONArray("notes");if(index<0)all.put(draft)else all.put(index,draft);data.remove("draft");data.remove("draftEditingId")}
        return draft
    }
    fun delete(id:String)=change{val index=notes.indexOfFirst{it.optString("id")==id};require(index>=0);val all=data.getJSONArray("notes");data.put("deletedNote",JSONObject().put("index",index).put("note",all.getJSONObject(index)));all.remove(index)}
    fun undoDelete():Boolean {
        val removed=data.optJSONObject("deletedNote") ?: return false
        val note=removed.getJSONObject("note");check(notes.none{it.optString("id")==note.optString("id")})
        change{val all=notes.toMutableList();all.add(removed.optInt("index",all.size).coerceIn(0,all.size),note);data.put("notes",JSONArray(all));data.remove("deletedNote")};return true
    }
}
