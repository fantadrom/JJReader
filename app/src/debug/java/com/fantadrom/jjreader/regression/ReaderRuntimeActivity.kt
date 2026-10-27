package com.fantadrom.jjreader.regression

import android.content.SharedPreferences
import com.fantadrom.jjreader.MainActivity
import java.io.File

/** Actual P0 UI/controller with an isolated per-test files/cache/prefs namespace. */
class ReaderRuntimeActivity : MainActivity() {
    override val prototypeControls get()=!intent.getBooleanExtra("p1Ui",false)
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        val prefs=getSharedPreferences("reader",MODE_PRIVATE)
        if(!prefs.contains("testInitialized"))prefs.edit().putBoolean("testInitialized",true).putInt("modeSchema",1).putString("mode",intent.getStringExtra("mode") ?: if(intent.getBooleanExtra("unified",false))"unified"else"legacy").commit()
        super.onCreate(savedInstanceState)
    }
    override fun localSpeechAudio(phrase:com.fantadrom.jjreader.tts.Phrase):File? {
        if(!intent.getBooleanExtra("localSpeech",false))return null
        val file=File(cacheDir,"local-fixture.wav")
        if(!file.exists()){
            val samples=8000;val bytes=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
            repeat(samples){i->bytes.putShort((kotlin.math.sin(i*2*Math.PI*440/16000)*1500).toInt().toShort())};file.writeBytes(bytes.array())
        };return file
    }
    private fun token() = (intent?.getStringExtra("testToken") ?: "reader-runtime").replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun root() = File(super.getCacheDir(), "runtime-regression/${token()}").apply { mkdirs() }
    override fun getFilesDir() = File(root(), "files").apply { mkdirs() }
    override fun getCacheDir() = File(root(), "cache").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("runtime-${token()}-$name", mode)
}
