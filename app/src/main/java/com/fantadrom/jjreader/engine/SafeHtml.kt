package com.fantadrom.jjreader.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Entities
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.Parser

/** Only the in-memory rendition is filtered. The imported EPUB stays byte-identical. */
object SafeHtml {
    fun transform(html: String, mediaType: String = "text/html"): String {
        val xhtml = mediaType.substringBefore(';').trim().equals("application/xhtml+xml", ignoreCase = true)
        // XML parsing preserves namespaces, attribute spelling and CDATA. XHTML must never
        // go through HTML serialization while retaining its original XML media type.
        val doc = if (xhtml) Jsoup.parse(html, "", Parser.xmlParser()) else Jsoup.parse(html)
        if (xhtml) {
            doc.selectFirst("html")?.let { if (!it.hasAttr("xmlns")) it.attr("xmlns", "http://www.w3.org/1999/xhtml") }
            doc.childNodes().filterIsInstance<DocumentType>().forEach { it.remove() }
            doc.childNodes().filterIsInstance<XmlDeclaration>().filter { it.name().equals("xml", true) }
                .forEach { it.attr("encoding", "UTF-8") }
        }
        doc.select("script,iframe,object,embed,form,base,meta[http-equiv],svg,math").remove()
        doc.allElements.forEach { element ->
            element.attributes().asList().filter { attr ->
                attr.key.startsWith("on", true) || attr.key in setOf("srcdoc", "srcset") ||
                    (attr.key in setOf("href", "src", "poster", "action", "xlink:href") &&
                        Regex("^(?i)([a-z][a-z0-9+.-]*:|//)").containsMatchIn(attr.value.trim()))
            }.forEach { element.removeAttr(it.key) }
        }
        doc.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content",
            // CSP host grammar rejects Readium's underscore virtual hosts. Restrict trusted
            // engine code/styles by HTTPS path; publication networking is independently denied
            // by ReadiumEngine's offline HttpClient and local-resource WebView interceptor.
            "default-src 'none'; script-src https://*/readium/scripts/readium-reflowable.js; style-src 'unsafe-inline' 'self' https://*/readium/readium-css/; img-src 'self' data:; font-src 'self' data: https://*/readium/fonts/ https://*/readium/readium-css/fonts/; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'")
        // Rendition-level CSS is present before every document loads, including prefetched pages.
        // Higher specificity than Readium's scroll body rule, independent of JS/capture readiness.
        doc.select("style#jj-reader-width").remove()
        doc.head().appendElement("style").attr("id", "jj-reader-width").text(
            "html:root[style*=readium-scroll-on] body{max-width:none!important;padding-left:16px!important;padding-right:16px!important}")
        doc.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
            .syntax(if (xhtml) Document.OutputSettings.Syntax.xml else Document.OutputSettings.Syntax.html)
            .escapeMode(if (xhtml) Entities.EscapeMode.xhtml else Entities.EscapeMode.base)
        return doc.outerHtml()
    }
}
