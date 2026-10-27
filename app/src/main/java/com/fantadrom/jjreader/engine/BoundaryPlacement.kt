package com.fantadrom.jjreader.engine

import org.json.JSONObject

/** Semantic entry, recomputed in CSS coordinates after every reflow. Never a stored scrollY. */
enum class BoundaryPlacement {
    RESOURCE_START, END_PAGE, END_TOP;

    fun saved():JSONObject? = if(this==END_TOP)null else JSONObject().put("schema",1).put("kind",name)
    companion object {
        fun read(value:JSONObject?):BoundaryPlacement? = value?.takeIf{it.optInt("schema")==1}
            ?.optString("kind")?.let{kind->entries.firstOrNull{it!=END_TOP && it.name==kind}}
    }
}
