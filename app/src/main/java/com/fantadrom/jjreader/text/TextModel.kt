package com.fantadrom.jjreader.text

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.security.MessageDigest

/** Canonical v1: spine order, code points, ASCII/NBSP whitespace, two LF per block.
 * No NFC conversion: combining marks and the original spelling remain intact. */
object TextModel {
    private val blocks = setOf("p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "tr", "td", "figcaption", "pre", "br")
    private val excluded = setOf("script", "style", "nav", "noscript", "iframe", "object", "svg", "math", "template")
    fun normalize(html: String): String {
        val out = StringBuilder()
        fun separator() {
            while (out.endsWith(" ")) out.setLength(out.length - 1)
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n\n")
        }
        fun visit(node: Node) {
            if (node is Element) {
                if (node.normalName() in excluded || node.hasAttr("hidden") || node.attr("aria-hidden") == "true") return
                if (node.normalName() in blocks) separator()
                node.childNodes().forEach(::visit)
                if (node.normalName() in blocks) separator()
            } else if (node is TextNode) {
                node.wholeText.codePoints().forEach { cp ->
                    if (cp in listOf(9, 10, 12, 13, 32, 160)) {
                        if (out.isNotEmpty() && !out.endsWith(" ") && !out.endsWith("\n")) out.append(' ')
                    } else out.appendCodePoint(cp)
                }
            }
        }
        visit(Jsoup.parse(html).body())
        return out.toString().trimEnd(' ', '\n')
    }
    fun length(text: String) = text.codePointCount(0, text.length)
    fun slice(text: String, start: Int, end: Int): String =
        text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
    fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun percent(end: Int, total: Int): Int = if (total == 0) 0 else if (end >= total) 100 else (end.toLong() * 100 / total).toInt().coerceIn(0, 99)
}
