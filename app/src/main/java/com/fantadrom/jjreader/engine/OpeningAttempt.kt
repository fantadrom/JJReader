package com.fantadrom.jjreader.engine

import com.fantadrom.jjreader.books.BookStore
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Separate bounded, durable preparation record; never accepts book text or exception messages. */
class OpeningAttempt(private val file:File) {
    private val lock=file.absolutePath.intern()
    enum class Stage { SOURCE_OPEN, ORDER_CHECK, CACHE_CHECK, BUILD, XML_VALIDATE, CACHE_PUBLISH, DERIVED_OPEN, NAVIGATOR_INIT }
    private fun read()=runCatching{BookStore.readAtomic(file)}.getOrElse{JSONObject().put("schema",1)}
    fun json():JSONObject=synchronized(lock){read()}
    fun begin(version:String,code:Long,unified:Boolean,activeMode:String?):String=synchronized(lock){
        val id=UUID.randomUUID().toString();val root=read()
        root.put("latest",JSONObject().put("attemptId",id).put("versionName",version).put("versionCode",code)
            .put("requestedMode",if(unified)"unified"else"legacy").put("activeMode",activeMode ?: JSONObject.NULL)
            .put("outcome","running").put("stage",Stage.SOURCE_OPEN.name).put("startedUtc",Instant.now().toString())
            .put("elapsedMs",0).put("converter",if(unified)UnifiedDocument.VERSION else JSONObject.NULL)
            .put("generation",JSONObject.NULL).put("cacheHit",JSONObject.NULL).put("errorCode",JSONObject.NULL)
            .put("exceptionClasses",org.json.JSONArray()).put("xmlProvider",JSONObject.NULL))
        BookStore.writeAtomic(file,root);id
    }
    fun isCurrent(id:String)=synchronized(lock){read().optJSONObject("latest")?.optString("attemptId")==id}
    private fun update(id:String,change:(JSONObject)->Unit)=synchronized(lock){
        val root=read();val row=root.optJSONObject("latest") ?: return@synchronized
        if(row.optString("attemptId")!=id)return@synchronized
        change(row);row.put("elapsedMs",(System.currentTimeMillis()-Instant.parse(row.getString("startedUtc")).toEpochMilli()).coerceAtLeast(0))
        if(row.optString("outcome")=="failed" && row.optString("requestedMode")=="unified")root.put("lastUnifiedFailure",JSONObject(row.toString()))
        BookStore.writeAtomic(file,root)
    }
    fun stage(id:String,stage:Stage)=update(id){it.put("stage",stage.name)}
    fun provider(id:String,name:String)=update(id){it.put("xmlProvider",name)}
    fun derived(id:String,document:UnifiedDocument)=update(id){it.put("generation",document.generation).put("cacheHit",document.cacheHit).put("prepareMs",document.prepareMs)}
    fun success(id:String,mode:String)=update(id){it.put("outcome","success").put("activeMode",mode)}
    fun failed(id:String,error:Exception)=update(id){row->
        val cancelled=error is CancellationException
        val code=when {
            cancelled->"CANCELLED"
            error is XhtmlValidationException->error.code
            error is UnifiedUnsupported->when {
                error.category.contains("CSS",true) || error.category.contains("font-face")->"UNSUPPORTED_CSS"
                error.category.contains("модел") || error.category.contains("readingOrder")->"UNSUPPORTED_MODEL"
                else->"UNSUPPORTED_RESOURCE"
            }
            row.optString("stage")==Stage.XML_VALIDATE.name->"XML_INVALID"
            else->"${row.optString("stage")}_FAILED"
        }
        val classes=org.json.JSONArray();var cause:Throwable?=error
        repeat(4){cause?.let{classes.put(it.javaClass.name);cause=it.cause}}
        row.put("outcome",if(cancelled)"cancelled"else"failed").put("errorCode",code).put("exceptionClasses",classes)
    }
}
