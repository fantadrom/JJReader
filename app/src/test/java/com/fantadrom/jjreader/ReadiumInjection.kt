@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import kotlinx.coroutines.runBlocking
import org.readium.r2.navigator.epub.css.UserProperties
import org.readium.r2.navigator.epub.css.View
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.StringResource

/** Test-only access to the exact injection code in the pinned Readium AAR, not a reimplementation.
 * Fail loudly when upgrading Readium: its internal JVM signature is intentionally checked here. */
object ReadiumInjection {
    fun apply(filtered: String, mediaType: String): String = runBlocking {
        val assets = checkNotNull(AbsoluteUrl("https://readium_assets/"))
        val cssType = Class.forName("org.readium.r2.navigator.epub.css.ReadiumCss")
        val constructor = cssType.constructors.single { it.parameterCount == 8 }
        val css = constructor.newInstance(null, null, UserProperties(view = View.SCROLL, pageMargins = 2.0), null, null, assets, 27, null)
        val publication = Publication(Manifest(metadata = Metadata(layout = Layout.REFLOWABLE)))
        try {
            val resource = Class.forName("org.readium.r2.navigator.epub.HtmlInjectorKt").methods.single { it.name == "injectHtml" }
                .invoke(null, StringResource(filtered), publication, checkNotNull(MediaType(mediaType)), css, assets, false) as Resource
            try { resource.read().getOrNull()?.toString(Charsets.UTF_8) ?: error("Readium injection failed") }
            finally { resource.close() }
        } finally { publication.close() }
    }
}
