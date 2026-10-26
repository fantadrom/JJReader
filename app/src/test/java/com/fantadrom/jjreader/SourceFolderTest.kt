package com.fantadrom.jjreader

import android.content.*
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.fantadrom.jjreader.books.SourceFolder
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SourceFolderTest {
    class Provider:ContentProvider(){
        var denied=false
        override fun onCreate()=true
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):android.database.Cursor {
            if(denied)throw SecurityException("Permission revoked")
            return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE)).apply{
                addRow(arrayOf("root/folder","Подпапка",DocumentsContract.Document.MIME_TYPE_DIR));addRow(arrayOf("root/book","Роман.EPUB","application/octet-stream"));addRow(arrayOf("root/text","Не EPUB.txt","text/plain"))
            }
        }
        override fun getType(uri:Uri)="vnd.android.document/directory"
        override fun insert(uri:Uri,v:ContentValues?):Uri?=error("Read only")
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?)=error("Read only")
        override fun delete(uri:Uri,s:String?,a:Array<out String>?)=error("Original files must not be deleted")
    }
    @Test fun persistedTreeFiltersAndBuildsReadOnlyUrisAndReportsRevocation() {
        val context=RuntimeEnvironment.getApplication();val provider=Provider();ShadowContentResolver.registerProviderInternal("test-p1-folder",provider)
        val folder=SourceFolder(context);val tree=Uri.parse("content://test-p1-folder/tree/root");folder.remember(tree)
        assertEquals(tree,SourceFolder(context).saved());val entries=folder.list(tree);assertEquals(2,entries.size);assertTrue(entries[0].directory);assertFalse(entries[1].directory)
        assertEquals("root/book",DocumentsContract.getDocumentId(entries[1].uri));assertEquals(tree.authority,entries[1].uri.authority)
        provider.denied=true
        try{folder.list(tree);fail("Lost permission")}catch(_:SecurityException){}
        assertEquals(tree,SourceFolder(context).saved()) // Failure never clears the library or changes the saved selection.
    }
}
