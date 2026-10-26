@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader.engine

import android.content.Context
import com.fantadrom.jjreader.books.Chapter
import com.fantadrom.jjreader.text.TextModel
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.TransformingResource
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

class ReadiumEngine(context: Context) {
    // No publication can fetch remote resources. TTS uses a separate, explicit adapter.
    private val offline = object : HttpClient {
        override suspend fun stream(request: org.readium.r2.shared.util.http.HttpRequest) =
            Try.failure(HttpError.IO(java.io.IOException("External EPUB resources disabled")))
    }
    private val assets = AssetRetriever(context.contentResolver, offline)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient = offline, assetRetriever = assets, pdfFactory = null))
    suspend fun open(file: File): Publication {
        val asset = assets.retrieve(file.toUrl(isDirectory = false)).getOrNull() ?: error("Не удалось распознать EPUB")
        val pub = opener.open(asset, allowUserInteraction = false, onCreatePublication = {
            val html = (manifest.readingOrder + manifest.resources).filter { it.mediaType?.isHtml == true }
                .associate { it.url().removeFragment() to checkNotNull(it.mediaType) }
            container = TransformingContainer(container) { url, resource ->
                val mediaType = html[url.removeFragment()]
                if (mediaType == null) resource else TransformingResource(resource) { bytes ->
                    Try.success(SafeHtml.transform(bytes.toString(Charsets.UTF_8), mediaType.toString()).toByteArray(Charsets.UTF_8))
                }
            }
        }).getOrNull() ?: error("Повреждённый, защищённый или неподдерживаемый EPUB")
        if (pub.metadata.layout == Layout.FIXED) { pub.close(); error("EPUB с фиксированной раскладкой не поддерживается в P0") }
        return pub
    }
    suspend fun chapters(pub: Publication): List<Chapter> = pub.readingOrder.mapIndexed { index, link ->
        val bytes = pub.get(link)?.read()?.getOrNull() ?: error("Не удалось прочитать ресурс EPUB")
        fun links(items: List<org.readium.r2.shared.publication.Link>): List<org.readium.r2.shared.publication.Link> = items.flatMap { listOf(it) + links(it.children) }
        val title = links(pub.tableOfContents).firstOrNull { it.url().removeFragment() == link.url().removeFragment() }?.title
            ?: link.title ?: "Раздел ${index + 1}"
        Chapter(link.url().toString(), title, if (link.mediaType?.isHtml == true) TextModel.normalize(bytes.toString(Charsets.UTF_8)) else "")
    }
}
