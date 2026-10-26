package com.fantadrom.jjreader.engine

import android.content.SharedPreferences

object ReadingMode {
    fun current(prefs:SharedPreferences):String {
        if(prefs.getInt("modeSchema",0)<1)prefs.edit().putInt("modeSchema",1).putString("mode","sections").commit()
        return prefs.getString("mode","sections").takeIf{it in setOf("sections","unified","legacy")} ?: "sections"
    }
    fun set(prefs:SharedPreferences,mode:String){require(mode in setOf("sections","unified","legacy"));prefs.edit().putInt("modeSchema",1).putString("mode",mode).commit()}
}
