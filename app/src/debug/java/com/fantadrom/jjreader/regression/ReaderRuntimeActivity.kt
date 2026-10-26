package com.fantadrom.jjreader.regression

import android.content.SharedPreferences
import com.fantadrom.jjreader.MainActivity
import java.io.File

/** Actual P0 UI/controller with an isolated per-test files/cache/prefs namespace. */
class ReaderRuntimeActivity : MainActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val prefs=getSharedPreferences("reader",MODE_PRIVATE)
        if(!prefs.contains("testInitialized"))prefs.edit().putBoolean("testInitialized",true).putBoolean("unified",intent.getBooleanExtra("unified",false)).commit()
        super.onCreate(savedInstanceState)
    }
    private fun token() = (intent?.getStringExtra("testToken") ?: "reader-runtime").replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun root() = File(super.getCacheDir(), "runtime-regression/${token()}").apply { mkdirs() }
    override fun getFilesDir() = File(root(), "files").apply { mkdirs() }
    override fun getCacheDir() = File(root(), "cache").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("runtime-${token()}-$name", mode)
}
