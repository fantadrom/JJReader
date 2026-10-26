package com.fantadrom.jjreader.ui

import android.content.SharedPreferences
import android.content.res.Configuration

object Appearance {
    val fonts=listOf("serif","sans-serif","monospace")
    val fontNames=arrayOf("С засечками","Без засечек","Моноширинный")
    fun dark(prefs:SharedPreferences,configuration:Configuration)=when(prefs.getString("theme","auto")){"dark"->true;"light"->false;else->configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES}
    fun font(prefs:SharedPreferences)=prefs.getString("font","serif").takeIf{it in fonts} ?: "serif"
    fun brightness(value:Float)=if(value.isFinite())value.coerceIn(.05f,1f)else .5f
}
