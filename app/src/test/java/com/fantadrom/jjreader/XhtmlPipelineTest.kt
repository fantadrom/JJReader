package com.fantadrom.jjreader

import com.fantadrom.jjreader.engine.SafeHtml
import com.fantadrom.jjreader.text.TextModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Document
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.StringReader
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class XhtmlPipelineTest {
    @Test fun minimumXhtmlRemainsWellFormedAfterRealFilter() {
        val input = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Проверка</title><meta charset="utf-8"/><link rel="stylesheet" href="book.css"/></head><body><p>Кириллица</p></body></html>"""
        strictXml(input)
        strictXml(SafeHtml.transform(input, "application/xhtml+xml"))
    }
    @Test fun embeddedEpubResourcesRemainWellFormedAfterRealFilter() {
        ZipFile(File("src/main/assets/p0-sample.epub")).use { zip ->
            val chapters = zip.entries().asSequence().filter { it.name.matches(Regex("EPUB/c[1-3]\\.xhtml")) }.toList()
            assertEquals(3, chapters.size)
            chapters.forEach { entry ->
                val input = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                strictXml(input)
                strictXml(SafeHtml.transform(input, "application/xhtml+xml"))
            }
        }
    }
    @Test fun fullResourceAfterRealReadiumInjectionIsValidXhtmlAndExportsDomFixtures() {
        val inputs = linkedMapOf("void-unicode" to File("src/test/resources/xhtml/void-unicode.xhtml").readText())
        ZipFile(File("src/main/assets/p0-sample.epub")).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("EPUB/c[1-3]\\.xhtml")) }.forEach { entry ->
                inputs[entry.name.substringAfterLast('/').substringBefore('.')] = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        }
        val output = File("build/xhtml-regression").apply { mkdirs() }
        val fixtures = JSONArray()
        inputs.forEach { (name, input) ->
            val safe = SafeHtml.transform(input, "application/xhtml+xml")
            val safeDoc = strictXml(safe)
            val injected = ReadiumInjection.apply(safe, "application/xhtml+xml")
            val document = strictXml(injected)
            assertEquals("http://www.w3.org/1999/xhtml", document.documentElement.namespaceURI)
            assertEquals(0, safeDoc.getElementsByTagName("script").length)
            assertEquals(1, document.getElementsByTagName("script").length)
            assertTrue(document.getElementsByTagName("link").length >= 2)
            assertEquals(TextModel.normalize(safe), TextModel.normalize(injected))
            if (name == "void-unicode") {
                listOf("meta", "link", "img", "br", "hr").forEach { assertTrue(document.getElementsByTagName(it).length > 0) }
                val paragraph = document.getElementsByTagName("p").item(0)
                assertTrue(paragraph.textContent.contains("ёж, е\u0308лка, 👩‍🚀"))
                assertTrue(paragraph.textContent.contains("\u00a0 © … & < >"))
                assertEquals("book.css?x=1&y=2", document.getElementsByTagName("link").let { links ->
                    (0 until links.length).map { links.item(it).attributes.getNamedItem("href").nodeValue }.first { it.startsWith("book.css") }
                })
                assertEquals("http://www.idpf.org/2007/ops", document.getElementsByTagName("a").item(0).attributes.getNamedItemNS("http://www.idpf.org/2007/ops", "type").namespaceURI)
                assertEquals("ru", document.documentElement.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang"))
            }
            File(output, "$name.safe.xhtml").writeText(safe)
            File(output, "$name.injected.xhtml").writeText(injected)
            fixtures.put(JSONObject().put("file", "$name.injected.xhtml").put("safe", "$name.safe.xhtml").put("normalized", TextModel.normalize(safe)))
        }
        File(output, "manifest.json").writeText(JSONObject().put("readium", "3.4.0").put("fixtures", fixtures).toString(2))
    }
    @Test fun htmlAndXhtmlUseTheirOwnSerializationAndKeepGuards() {
        val input = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Защита</title></head><body onload="steal()"><p onclick="x()">Текст</p><script>steal()</script><img src="https://example.com/x"/><img src="local.png"/><a href="javascript:x()">ссылка</a><iframe srcdoc="x"/></body></html>"""
        val xhtml = SafeHtml.transform(input, "application/xhtml+xml; charset=utf-8")
        val doc = strictXml(xhtml)
        assertEquals(0, doc.getElementsByTagName("script").length)
        assertEquals(0, doc.getElementsByTagName("iframe").length)
        assertEquals("", (doc.getElementsByTagName("img").item(0) as org.w3c.dom.Element).getAttribute("src"))
        assertEquals("local.png", (doc.getElementsByTagName("img").item(1) as org.w3c.dom.Element).getAttribute("src"))
        assertEquals("", (doc.getElementsByTagName("body").item(0) as org.w3c.dom.Element).getAttribute("onload"))
        assertTrue(xhtml.contains("connect-src 'none'"))
        val html = SafeHtml.transform("<p>Текст<br>дальше</p>", "text/html")
        assertTrue(html.contains("<br>"))
        assertEquals(TextModel.normalize(html), TextModel.normalize(SafeHtml.transform("<html><head></head><body><p>Текст<br/>дальше</p></body></html>", "application/xhtml+xml")))
    }
    companion object {
        fun strictXml(xml: String): Document {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
            }
            return factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
                setErrorHandler(object : DefaultHandler() {
                    override fun error(e: SAXParseException) = throw e
                    override fun fatalError(e: SAXParseException) = throw e
                })
            }.parse(InputSource(StringReader(xml)))
        }
    }
}
