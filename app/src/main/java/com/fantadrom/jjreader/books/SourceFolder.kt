package com.fantadrom.jjreader.books

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import android.net.Uri
import android.provider.DocumentsContract

/** Read-only SAF browser. Original files are never written or deleted. */
class SourceFolder(private val context:Context) {
    data class Entry(val name:String,val uri:Uri,val directory:Boolean)
    private val prefs=context.getSharedPreferences("source-folder",Context.MODE_PRIVATE)
    fun saved():Uri?=prefs.getString("tree",null)?.let(Uri::parse)
    fun remember(tree:Uri):Boolean {
        val previous=saved()
        val retained=runCatching{context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);true}.getOrDefault(false)
        if(previous!=null && previous!=tree)runCatching{context.contentResolver.releasePersistableUriPermission(previous,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        prefs.edit { putString("tree",tree.toString());putBoolean("retained",retained) }
        return retained
    }
    fun list(tree:Uri,documentId:String=DocumentsContract.getTreeDocumentId(tree)):List<Entry> {
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,documentId)
        return context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)?.use{cursor->
            val result=mutableListOf<Entry>()
            while(cursor.moveToNext()){
                val id=cursor.getString(0);val name=cursor.getString(1) ?: "Без имени";val mime=cursor.getString(2)
                val directory=mime==DocumentsContract.Document.MIME_TYPE_DIR
                if(directory || mime=="application/epub+zip" || name.endsWith(".epub",true))result+=Entry(name,DocumentsContract.buildDocumentUriUsingTree(tree,id),directory)
            };result.sortedWith(compareByDescending<Entry>{it.directory}.thenBy{it.name})
        } ?: error("Папка недоступна. Выберите её снова; внутренние книги сохранены.")
    }
}
