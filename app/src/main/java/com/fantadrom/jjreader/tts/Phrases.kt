package com.fantadrom.jjreader.tts

import com.fantadrom.jjreader.text.TextModel
import java.text.BreakIterator
import java.util.Locale

data class Phrase(val start: Int, val end: Int, val text: String)
object Phrases {
    fun split(text: String): List<Phrase> {
        val iterator = BreakIterator.getSentenceInstance(Locale.forLanguageTag("ru"))
        iterator.setText(text)
        val ends = mutableListOf<Int>()
        var end = iterator.first()
        while (end != BreakIterator.DONE) {
            if (end > 0) {
                val prefix = text.substring(0, end).trimEnd()
                if (!Regex("(?:^|\\s)(?:[А-ЯЁA-Z]|г|ул|т|д|п|им|стр)\\.$", RegexOption.IGNORE_CASE).containsMatchIn(prefix) || end == text.length) ends += end
            }
            end = iterator.next()
        }
        if (ends.lastOrNull() != text.length) ends += text.length
        var start = 0
        return ends.flatMap { boundary ->
            val list = mutableListOf<Phrase>()
            var cursor = start
            while (cursor < boundary) {
                while (cursor < boundary && text[cursor].isWhitespace()) cursor++
                if (cursor >= boundary) break
                val cp = text.codePointCount(cursor, boundary)
                var limit = if (cp <= 600) boundary else text.offsetByCodePoints(cursor, 600)
                if (limit < boundary) {
                    val word = text.lastIndexOf(' ', limit)
                    limit = if (word > cursor) word else BreakIterator.getCharacterInstance(Locale.ROOT).run { setText(text); preceding(limit + 1) }
                }
                val value = text.substring(cursor, limit).trimEnd()
                list += Phrase(text.codePointCount(0, cursor), text.codePointCount(0, cursor + value.length), value)
                cursor = limit
            }
            start = boundary
            list
        }
    }
    fun cacheKey(text: String, voice: Voice) = TextModel.hash(("p0-v2\u0000${voice.wire}\u0000+0%\u0000audio-24khz-48kbitrate-mono-mp3\u0000$text").toByteArray())
}
