package com.fantadrom.jjreader.notes

import org.json.JSONObject

/** Bound total paint opacity at every point; five overlapping notes must not wash out night text. */
object NoteLayers {
    fun depth(notes:List<JSONObject>):Map<String,Int> {
        val result=linkedMapOf<String,Int>()
        val colored=notes.filter{NoteColor.of(it.optString("color"))!=null&&it.optInt("chapter",-1)>=0&&it.optInt("start",-1)>=0&&it.optInt("end")>it.optInt("start")}
        for(rows in colored.groupBy{it.getInt("chapter")}.values){
            val active=linkedSetOf<String>()
            val starts=rows.groupBy{it.getInt("start")};val ends=rows.groupBy{it.getInt("end")}
            for(point in (starts.keys+ends.keys).sorted()){
                ends[point].orEmpty().forEach{active.remove(it.getString("id"))}
                starts[point].orEmpty().forEach{active+=it.getString("id")}
                active.forEach{id->result[id]=maxOf(result[id] ?: 1,active.size)}
            }
        }
        return result
    }
}
