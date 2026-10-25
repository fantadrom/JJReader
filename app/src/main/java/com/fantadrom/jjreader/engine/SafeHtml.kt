package com.fantadrom.jjreader.engine

import org.jsoup.Jsoup

/** Only the in-memory rendition is filtered. The imported EPUB stays byte-identical. */
object SafeHtml {
    fun transform(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script,iframe,object,embed,form,base,meta[http-equiv],svg,math").remove()
        doc.allElements.forEach { element ->
            element.attributes().asList().filter { attr ->
                attr.key.startsWith("on", true) || attr.key in setOf("srcdoc", "srcset") ||
                    (attr.key in setOf("href", "src", "poster", "action", "xlink:href") &&
                        Regex("^(?i)([a-z][a-z0-9+.-]*:|//)").containsMatchIn(attr.value.trim()))
            }.forEach { element.removeAttr(it.key) }
        }
        doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
            "default-src 'none'; script-src https://readium_assets; style-src 'unsafe-inline' https://readium_assets https://readium_package; img-src https://readium_package data:; font-src https://readium_package https://readium_assets data:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'")
        doc.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
        return doc.outerHtml()
    }
}
