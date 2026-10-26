package com.fantadrom.jjreader.engine

import com.fantadrom.jjreader.books.Book
import com.fantadrom.jjreader.text.TextModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class UnifiedUnsupported(val category: String) : IllegalStateException("Единая лента недоступна: $category. Доступен прежний режим в диагностике.")
data class UnifiedDocument(val file: File, val map: JSONObject, val cacheHit: Boolean, val prepareMs: Long) {
    val generation: String get() = map.getString("generation")
    companion object {
        const val VERSION="unified-v1";const val HREF="unified.xhtml"
        private val mutex=Mutex()
        suspend fun prepare(book: Book, root: File, toc: List<Pair<String,String>>, onStage:(OpeningAttempt.Stage)->Unit={}, onProvider:(String)->Unit={}): UnifiedDocument = mutex.withLock {
            require(UUID.fromString(book.id).toString()==book.id)
            onStage(OpeningAttempt.Stage.CACHE_CHECK)
            val began=System.nanoTime();currentCoroutineContext().ensureActive()
            com.fantadrom.jjreader.books.BookStore.validateZip(book.file)
            val sourceHash=hashFile(book.file)
            val modelHash=TextModel.hash(book.chapters.joinToString("\u0000"){it.href+"\u0000"+TextModel.hash(it.text.toByteArray())}.toByteArray())
            val key=TextModel.hash("$VERSION|model1|${book.id}|$sourceHash|$modelHash".toByteArray())
            val parent=File(root,book.id).apply{mkdirs()};val complete=File(parent,key)
            fun ready():JSONObject?=runCatching {
                val marker=JSONObject(File(complete,"ready.json").readText());val map=File(complete,"map.json")
                check(marker.getString("epubHash")==hashFile(File(complete,"rendition.epub")))
                check(marker.getString("mapHash")==hashFile(map));JSONObject(map.readText()).also{check(it.getString("sourceHash")==sourceHash && it.getString("modelHash")==modelHash)}
            }.getOrNull()
            ready()?.let{return@withLock UnifiedDocument(File(complete,"rendition.epub"),it,true,(System.nanoTime()-began)/1_000_000)}
            // Only discard this derived cache, never the source or reader.json.
            if(complete.exists())complete.deleteRecursively()
            parent.listFiles()?.filter{it.name.startsWith(".stage-")}?.forEach{it.deleteRecursively()}
            val stage=File(parent,".stage-${UUID.randomUUID()}").apply{check(mkdir())}
            try {
                val generation=UUID.randomUUID().toString()
                val builder=UnifiedBuilder(book)
                ZipFile(book.file).use { zip ->
                    onStage(OpeningAttempt.Stage.BUILD)
                    builder.build(zip,toc,generation,onStage,onProvider)
                    onStage(OpeningAttempt.Stage.BUILD)
                    currentCoroutineContext().ensureActive()
                    val out=File(stage,"rendition.epub")
                    out.outputStream().use { stream -> ZipOutputStream(stream).use { target ->
                        fun write(name:String,bytes:ByteArray,stored:Boolean=false){val entry=ZipEntry(name)
                            if(stored){entry.method=ZipEntry.STORED;entry.size=bytes.size.toLong();entry.crc=CRC32().apply{update(bytes)}.value}
                            target.putNextEntry(entry);target.write(bytes);target.closeEntry()}
                        write("mimetype","application/epub+zip".toByteArray(),true)
                        write("META-INF/container.xml","""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
                        write(HREF,builder.xhtml.toByteArray())
                        write("nav.xhtml",builder.navigation.toByteArray())
                        val resources=mutableListOf<Pair<String,String>>()
                        zip.entries().asSequence().filter{!it.isDirectory && !it.name.endsWith(".opf",true) && !it.name.startsWith("META-INF/") && it.name!="mimetype"}.forEach { entry ->
                            currentCoroutineContext().ensureActive()
                            // Original HTML is not a second display route. All reading links were mapped.
                            if(entry.name.substringAfterLast('.').lowercase() !in setOf("xhtml","html","htm","ncx")) {
                                val name="source/${entry.name}";resources+=name to mediaType(entry.name)
                                target.putNextEntry(ZipEntry(name));zip.getInputStream(entry).use{it.copyTo(target)};target.closeEntry()
                            }
                        }
                        val manifest=resources.mapIndexed{i,p->"<item id='r$i' href='${xml(p.first)}' media-type='${p.second}'/>"}.joinToString("")
                        write("content.opf","""<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">$generation</dc:identifier><dc:title>JJReader rendition</dc:title><dc:language>ru</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="main" href="$HREF" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest><spine><itemref idref="main"/></spine></package>""".toByteArray())
                    } }
                    java.io.RandomAccessFile(out,"rw").use{it.fd.sync()}
                }
                val map=builder.map.put("schema",1).put("converter",VERSION).put("generation",generation).put("sourceHash",sourceHash).put("modelHash",modelHash)
                File(stage,"map.json").writeText(map.toString());java.io.RandomAccessFile(File(stage,"map.json"),"rw").use{it.fd.sync()}
                File(stage,"ready.json").writeText(JSONObject().put("epubHash",hashFile(File(stage,"rendition.epub"))).put("mapHash",hashFile(File(stage,"map.json"))).toString())
                java.io.RandomAccessFile(File(stage,"ready.json"),"rw").use{it.fd.sync()}
                onStage(OpeningAttempt.Stage.CACHE_PUBLISH)
                currentCoroutineContext().ensureActive();check(stage.renameTo(complete))
                UnifiedDocument(File(complete,"rendition.epub"),map,false,(System.nanoTime()-began)/1_000_000)
            } finally {stage.deleteRecursively()}
        }
        internal fun hashFile(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
        internal fun xml(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace("'","&apos;").replace("\"","&quot;")
        internal fun mediaType(path:String)=when(path.substringAfterLast('.').lowercase()){"css"->"text/css";"jpg","jpeg"->"image/jpeg";"png"->"image/png";"gif"->"image/gif";"webp"->"image/webp";"avif"->"image/avif";"bmp"->"image/bmp";"otf"->"font/otf";"ttf"->"font/ttf";"woff"->"font/woff";"woff2"->"font/woff2";else->"application/octet-stream"}
    }
}

/** DOM transformation: one wrapper per immutable source chapter, no text concatenation coordinates. */
internal class UnifiedBuilder(private val book:Book) {
    lateinit var xhtml:String;lateinit var navigation:String
    val map=JSONObject()
    private val paths=book.chapters.map{path(it.href)}
    private fun path(value:String)=URI(value.replace(" ","%20")).path.trimStart('/')
    private fun resolve(base:String,href:String):URI {
        val uri=URI(null,null,base,null).resolve(href.replace(" ","%20")).normalize()
        if(uri.isAbsolute || uri.rawAuthority!=null || uri.path.startsWith("../") || uri.path.startsWith('/'))throw UnifiedUnsupported("внешний или небезопасный путь")
        return uri
    }
    private fun id(index:Int,value:String)="jj-$index-"+value
    private fun link(base:String,raw:String):String {
        if(raw.isBlank() || raw.startsWith("data:"))return raw
        val uri=resolve(base,raw);val index=paths.indexOf(uri.path)
        if(index>=0)return "#"+if(uri.fragment.isNullOrEmpty())"jj-source-$index"else id(index,uri.fragment)
        if(uri.path.substringAfterLast('.').lowercase() in setOf("xhtml","html","htm"))throw UnifiedUnsupported("ссылка на нелинейный HTML вне модели")
        return URI(null,null,"source/"+uri.path,uri.query,uri.fragment).toASCIIString()
    }
    suspend fun build(zip:ZipFile,toc:List<Pair<String,String>>,generation:String,onStage:(OpeningAttempt.Stage)->Unit={},onProvider:(String)->Unit={}) {
        if(paths.distinct().size!=paths.size)throw UnifiedUnsupported("повторяющиеся readingOrder href неоднозначны для резервного режима")
        if(book.chapters.isEmpty())throw UnifiedUnsupported("нет линейных разделов")
        zip.entries().asSequence().filter{it.name.endsWith(".opf",true)}.forEach{opf->
            val packageDoc=Jsoup.parse(zip.getInputStream(opf).bufferedReader().use{it.readText()},"",Parser.xmlParser())
            val items=packageDoc.select("manifest item").associate{it.attr("id") to it.attr("href")}
            packageDoc.select("spine itemref[linear=no]").forEach{item->
                val href=items[item.attr("idref")] ?: return@forEach
                val target=resolve(opf.name,href).path
                if(target in paths)throw UnifiedUnsupported("нелинейный ресурс включён в сохранённую модель")
            }
        }
        val doc=Jsoup.parse("<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>JJReader</title></head><body></body></html>","",Parser.xmlParser())
        doc.selectFirst("html")!!.attr("data-jj-generation",generation)
        val css=UnifiedCss({base,url->link(base,url)},{p->zip.getEntry(p)?.let{zip.getInputStream(it).bufferedReader().use{r->r.readText()}}})
        val sections=JSONArray();var prefix=0
        book.chapters.forEachIndexed { index,chapter ->
            currentCoroutineContext().ensureActive()
            val source=paths[index];val entry=zip.getEntry(source) ?: throw UnifiedUnsupported("исходный ресурс не найден")
            val raw=zip.getInputStream(entry).bufferedReader().use{it.readText()}
            val safe=SafeHtml.transform(raw,if(source.endsWith(".html",true))"text/html"else"application/xhtml+xml")
            val input=Jsoup.parse(safe,"",Parser.xmlParser());val body=input.selectFirst("body") ?: throw UnifiedUnsupported("нет body")
            if(TextModel.normalize(safe)!=chapter.text)throw UnifiedUnsupported("канонический текст модели изменён")
            val wrapper=doc.body().appendElement("section").attr("id","jj-source-$index").attr("data-jj-source",index.toString())
            val content=body.clone().tagName("div").attr("data-jj-body",index.toString())
            content.select("nav").remove()
            content.select("img[loading]").forEach{it.removeAttr("loading")} // One eagerly laid-out document; no unresolved lazy-image height changes.
            for(attr in listOf("lang","xml:lang","dir"))if(!content.hasAttr(attr))input.selectFirst("html")?.attr(attr)?.takeIf{it.isNotBlank()}?.let{content.attr(attr,it)}
            val bodyId=content.id().takeIf{it.isNotEmpty()}
            content.attr("id",bodyId?.let{id(index,it)}?:"jj-body-$index")
            input.select("head style:not(#jj-reader-width), head link[rel=stylesheet]").forEach{element->
                val sheet=if(element.tagName()=="style")css.transform(element.data().ifEmpty{element.text()},source,index,content.id())else css.load(resolve(source,element.attr("href")).path,index,content.id())
                doc.head().appendElement("style").text(sheet)
            }
            content.allElements.forEach{element->
                if(element!==content && element.hasAttr("id"))element.attr("id",id(index,element.id()))
                for(attr in listOf("href","src","poster"))if(element.hasAttr(attr))element.attr(attr,link(source,element.attr(attr)))
                for(attr in listOf("for","headers","aria-labelledby","aria-describedby"))if(element.hasAttr(attr))element.attr(attr,element.attr(attr).split(Regex("\\s+")).joinToString(" "){id(index,it)})
                if(element.hasAttr("style"))element.attr("style",css.declarations(element.attr("style"),source))
            }
            content.attr("style",content.attr("style")+";margin:0!important;padding:0!important;width:auto!important;max-width:none!important;min-width:0!important;height:auto!important;min-height:0!important;float:none!important;position:static!important;overflow:visible!important")
            wrapper.appendChild(content)
            if(TextModel.normalize("<body>${wrapper.outerHtml()}</body>")!=chapter.text)throw UnifiedUnsupported("неоднозначное DOM-сопоставление")
            val length=TextModel.length(chapter.text)
            sections.put(JSONObject().put("index",index).put("href",chapter.href).put("length",length).put("prefix",prefix).put("textHash",TextModel.hash(chapter.text.toByteArray())).put("wrapper","jj-source-$index"))
            prefix+=length
        }
        doc.head().appendElement("style").text("[data-jj-source]{display:block!important;clear:both!important;margin:0!important;padding:0!important;break-before:auto!important;break-after:auto!important}[data-jj-body]{display:flow-root!important;width:auto!important;margin:0!important;padding:0!important;max-width:none!important;height:auto!important;min-height:0!important;columns:auto!important}")
        val available=if(toc.isNotEmpty())toc else book.chapters.map{it.title to it.href}
        val tocJson=JSONArray()
        available.forEach{(title,href)->val uri=resolve("unified.xhtml",href);val chapter=paths.indexOf(uri.path);if(chapter>=0)tocJson.put(JSONObject().put("title",title).put("chapter",chapter).put("target",if(uri.fragment.isNullOrEmpty())"jj-source-$chapter"else id(chapter,uri.fragment)))}
        map.put("sections",sections).put("toc",tocJson).put("authorToc",toc.isNotEmpty()).put("total",prefix)
        val links=(0 until tocJson.length()).joinToString(""){val t=tocJson.getJSONObject(it);"<li><a href='${UnifiedDocument.HREF}#${UnifiedDocument.xml(t.getString("target"))}'>${UnifiedDocument.xml(t.getString("title"))}</a></li>"}
        navigation="""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>$links</ol></nav></body></html>"""
        doc.outputSettings().prettyPrint(false).syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
        xhtml=SafeHtml.transform(doc.outerHtml(),"application/xhtml+xml")
        onStage(OpeningAttempt.Stage.XML_VALIDATE)
        AndroidXhtmlValidator.validate(xhtml,onProvider)
    }
}
