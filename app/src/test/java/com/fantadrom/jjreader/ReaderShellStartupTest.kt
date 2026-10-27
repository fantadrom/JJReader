package com.fantadrom.jjreader

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.fantadrom.jjreader.regression.ReaderRuntimeActivity
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ReaderShellStartupTest {
    @Test fun actualP1ActivityStartsWithAccessibleShelfWithoutCreatingUserReaderData() {
        val intent=Intent(RuntimeEnvironment.getApplication(),ReaderRuntimeActivity::class.java).putExtra("p1Ui",true).putExtra("mode","sections").putExtra("testToken",UUID.randomUUID().toString())
        val controller=Robolectric.buildActivity(ReaderRuntimeActivity::class.java,intent).setup().visible()
        try {
            val activity=controller.get()
            repeat(40){Thread.sleep(20);ShadowLooper.idleMainLooper()}
            fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
            val views=descendants(activity.findViewById(android.R.id.content))
            assertTrue(views.filterIsInstance<TextView>().any{it.text=="Папка EPUB" && it.isShown})
            assertTrue(views.filterIsInstance<TextView>().any{it.text=="Открыть файл" && it.isShown})
            assertFalse(views.filterIsInstance<TextView>().single{it.text=="К чтению"}.isEnabled)
            assertNull(activity.readerSessionForTests())
            assertTrue(java.io.File(activity.filesDir,"books-v1").listFiles().orEmpty().none{it.isDirectory})
        }finally{controller.pause().stop().destroy()}
    }
}
