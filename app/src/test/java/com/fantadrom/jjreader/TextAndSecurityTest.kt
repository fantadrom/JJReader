package com.fantadrom.jjreader

import com.fantadrom.jjreader.books.BookStore
import com.fantadrom.jjreader.engine.SafeHtml
import com.fantadrom.jjreader.text.TextModel
import com.fantadrom.jjreader.tts.Phrases
import com.fantadrom.jjreader.tts.Voice
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TextAndSecurityTest {
    @Test fun paragraphsInlineAndNbspHaveOneCanonicalModel() {
        assertEquals("Глава\n\nРаз два три\n\nКонец", TextModel.normalize("<h1>Глава</h1><p> Раз <b>два</b>\u00a0 три </p><div><p>Конец</p></div>"))
    }
    @Test fun serviceTextAltAndNavigationDoNotChangeProgress() {
        assertEquals("Текст", TextModel.normalize("<nav>Оглавление</nav><p>Текст<img alt='Подпись'/><span hidden>Скрыто</span></p><script>code</script><style>p{}</style>"))
    }
    @Test fun codePointsPreserveSurrogatesAndCombiningMarks() {
        val text = TextModel.normalize("<p>А🧭е\u0308Я</p>")
        assertEquals(5, TextModel.length(text)); assertEquals("🧭е\u0308", TextModel.slice(text, 1, 4))
    }
    @Test fun lastVisibleCharacterControlsWholeBookPercent() {
        assertEquals(0, TextModel.percent(0, 0)); assertEquals(99, TextModel.percent(9999, 10000))
        assertEquals(100, TextModel.percent(10000, 10000)); assertEquals(50, TextModel.percent(50, 100))
    }
    @Test fun identicalWordsRemainDifferentRanges() {
        val text = TextModel.normalize("<p>маяк один</p><p>маяк два</p>")
        assertEquals("маяк", TextModel.slice(text, 0, 4)); assertEquals("маяк", TextModel.slice(text, 11, 15))
    }
    @Test fun sanitizerPreservesReadingTextAndDisablesActiveContent() {
        val safe = SafeHtml.transform("<html><head><script>steal()</script></head><body onload='steal()'><p onclick='x()'>Книга</p><img src='https://example.com/a.png'/><iframe srcdoc='x'></iframe><a href='javascript:bad()'>ссылка</a></body></html>")
        val doc = Jsoup.parse(safe)
        assertEquals(0, doc.select("script,iframe,[onload],[onclick],[srcdoc]").size)
        assertEquals("", doc.selectFirst("img")!!.attr("src")); assertEquals("", doc.selectFirst("a")!!.attr("href"))
        assertTrue(doc.selectFirst("meta[http-equiv]")!!.attr("content").contains("connect-src 'none'"))
        assertEquals("Книга\n\nссылка", TextModel.normalize(safe))
    }
    @Test fun cacheSeparatesVoiceAndExactText() {
        assertNotEquals(Phrases.cacheKey("Привет", Voice.DMITRY), Phrases.cacheKey("Привет", Voice.SVETLANA))
        assertNotEquals(Phrases.cacheKey("Привет", Voice.DMITRY), Phrases.cacheKey("Привет!", Voice.DMITRY))
    }
    @Test fun phraseSegmentationKeepsAllNonWhitespaceAndExactOffsets() {
        val text = "И. И. Иванов приехал в г. Бишкек. — Подожди… — сказал он. Дальше 👩‍🚀."
        val phrases = Phrases.split(text)
        assertEquals(text.filterNot { it.isWhitespace() }, phrases.joinToString("") { it.text }.filterNot { it.isWhitespace() })
        phrases.forEach { assertEquals(it.text, TextModel.slice(text, it.start, it.end)) }
        assertFalse(phrases.any { it.text == "И." || it.text == "г." })
    }
    @Test fun boundedPhrasesNeverCutEmojiOrDropWords() {
        val text = ("🧭слово ".repeat(300)) + "Конец."
        val phrases = Phrases.split(text)
        assertTrue(phrases.all { TextModel.length(it.text) <= 600 })
        assertEquals(text.filterNot { it.isWhitespace() }, phrases.joinToString("") { it.text }.filterNot { it.isWhitespace() })
    }
    @Test fun simpleFiveSentencesRemainFivePlaybackPhrases() {
        assertEquals(5, Phrases.split("Утро было тихим. Поезд остановился у станции. Светлана открыла блокнот. Дмитрий проверил часы. Начинается дорога.").size)
    }
    @Test fun zipTraversalIsRejectedWithoutExtraction() {
        val file = File.createTempFile("malicious", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("META-INF/container.xml")); z.write("container".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("../outside")); z.write(1); z.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { BookStore.validateZip(file) }
        } finally { file.delete() }
    }
    @Test fun encryptedEpubIsRejectedExplicitly() {
        val file = File.createTempFile("encrypted", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { z ->
                for (path in listOf("META-INF/container.xml", "META-INF/encryption.xml")) { z.putNextEntry(ZipEntry(path)); z.write(1); z.closeEntry() }
            }
            assertTrue(assertThrows(IllegalArgumentException::class.java) { BookStore.validateZip(file) }.message!!.contains("DRM"))
        } finally { file.delete() }
    }
}
