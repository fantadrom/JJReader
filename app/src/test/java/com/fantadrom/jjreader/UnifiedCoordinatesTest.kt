package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.tts.*
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[35])
class UnifiedCoordinatesTest {
    private val book=Book("id","unused",File("unused"),listOf(Chapter("a.xhtml","a","Первая фраза. Вторая фраза."),Chapter("empty.xhtml","empty",""),Chapter("c.xhtml","c","Третья фраза. Четвёртая фраза. Пятая фраза.")))
    @Test fun sourcePrefixesDoNotAddBoundaryCharactersAndPhrasesKeepSections() {
        val map=RenditionAdapter(book)
        assertEquals(TextModel.length(book.chapters[0].text),map.absolute(2,0));assertEquals(2 to 0,map.boundary(1));assertEquals(2 to 0,map.sourceAt(map.absolute(2,0)));assertEquals(2 to TextModel.length(book.chapters[2].text),map.sourceAt(book.total))
        val queue=map.phrases(0,TextModel.length(book.chapters[0].text)-5)
        assertEquals(0,queue.first().chapter);assertTrue(queue.any{it.chapter==2});assertFalse(queue.any{it.text.contains("Вторая фраза.Третья")} )
        val sameOffsetDifferentText=Phrase(0,1,"другой",chapter=2)
        assertNotEquals(Phrases.cacheKey(queue.first().text,Voice.DMITRY),Phrases.cacheKey(sameOffsetDifferentText.text,Voice.DMITRY))
    }
    @Test fun savedLocatorStripsOnlyTechnicalWrapperAndRetainsRepeatedQuoteDomRange() {
        val adapter=RenditionAdapter(book)
        val selector="html:nth-of-type(1) > body:nth-of-type(1) > section:nth-of-type(3) > div:nth-of-type(1) > section:nth-of-type(1) > p:nth-of-type(2)"
        val anchor=JSONObject().put("start",0).put("quote","Третья").put("locations",JSONObject().put("cssSelector",selector)
            .put("domRange",JSONObject().put("start",JSONObject().put("cssSelector",selector).put("charOffset",7).put("textNodeIndex",0))))
        val locator=adapter.sourceLocator(2,anchor).toJSON();assertEquals("c.xhtml",locator.getString("href"))
        val original=locator.getJSONObject("locations").getString("cssSelector")
        assertEquals("html:nth-of-type(1) > body:nth-of-type(1) > section:nth-of-type(1) > p:nth-of-type(2)",original)
        assertEquals(7,locator.getJSONObject("locations").getJSONObject("domRange").getJSONObject("start").getInt("charOffset"))
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun deterministicPlaybackCrossesSourceBoundaryWithoutCombiningSentences()=kotlinx.coroutines.test.runTest {
        val adapter=RenditionAdapter(book);val spoken=mutableListOf<Int>();val highlights=mutableListOf<Int>()
        val phrases=adapter.phrases(0,TextModel.length(book.chapters[0].text)-5)
        val queue=SpeechQueue(this,SpeechDiagnostics(),{phrase,_,_->File("source-${phrase.chapter}")},
            {file,_,started->started();spoken+=file.name.substringAfter("source-").toInt()},
            {phrase->if(phrase!=null)highlights+=phrase.chapter},{},{true},{true},{},kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        queue.start(phrases,Voice.DMITRY);testScheduler.advanceUntilIdle()
        assertEquals(listOf(0,2,2,2),spoken);assertEquals(spoken,highlights);assertEquals(SpeechQueue.State.COMPLETED,queue.state)
    }

}
