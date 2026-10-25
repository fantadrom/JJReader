@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader.regression

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import com.fantadrom.jjreader.engine.ReadiumEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Publication
import java.io.File

/** Debug-only, unexported rendering host. It never changes the current book or saved notes. */
class RenderRegressionActivity : AppCompatActivity() {
    var navigator: EpubNavigatorFragment? = null; private set
    var publication: Publication? = null; private set
    var failure: Throwable? = null; private set
    private var fixture: File? = null
    private val readerContainerId = View.generateViewId()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        setContentView(FragmentContainerView(this).apply { id = readerContainerId })
        lifecycleScope.launch {
            try {
                val pub = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("xhtml-render-", ".epub", cacheDir).also { fixture = it }
                    assets.open("p0-sample.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
                    ReadiumEngine(this@RenderRegressionActivity).open(file)
                }
                publication = pub
                supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
                    initialLocator = null,
                    initialPreferences = EpubPreferences(scroll = true, fontSize = 1.0, pageMargins = 2.0, publisherStyles = false)
                )
                val nav = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
                navigator = nav
                supportFragmentManager.beginTransaction().add(readerContainerId, nav).commitNow()
            } catch (e: Exception) { failure = e }
        }
    }
    override fun onDestroy() { super.onDestroy(); publication?.close(); fixture?.delete() }
}
