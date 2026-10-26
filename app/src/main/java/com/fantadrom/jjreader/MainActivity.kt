@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.app.AlertDialog
import android.os.*
import android.text.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.*
import com.fantadrom.jjreader.books.*
import com.fantadrom.jjreader.engine.*
import com.fantadrom.jjreader.notes.NoteStore
import com.fantadrom.jjreader.text.TextModel
import com.fantadrom.jjreader.tts.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.readium.r2.navigator.*
import org.readium.r2.navigator.epub.*
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.util.UUID

/** P0 laboratory. The original Compose theme is retained for the next stage. */
open class MainActivity : AppCompatActivity() {
    private lateinit var readingTrace: ReadingTrace
    private val activityId = UUID.randomUUID().toString()
    private lateinit var store: BookStore
    private lateinit var engine: ReadiumEngine
    private lateinit var bridge: LocatorBridge
    private lateinit var player: PhrasePlayer
    private lateinit var heading: TextView
    private lateinit var progress: TextView
    private lateinit var status: TextView
    private lateinit var notesButton: Button
    private var book: Book? = null
    private var notes: NoteStore? = null
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var readerJob: Job? = null
    private val openingAttempts by lazy { OpeningAttempt(java.io.File(filesDir,"opening-attempt-v1.json")) }
    private var openingJob: Job? = null
    private var decorationJob:Job?=null
    private var resizeJob: Job? = null
    private var navigationJob: Job? = null
    private var busy = false
    private var voice = Voice.DMITRY
    private var verifiedHref: String? = null
    private var mappingValid = false
    private var visibleFirst = 0
    private var editor: AlertDialog? = null
    private var session: ReaderSession? = null
    private var selectionSession: String? = null
    private var speechChapter: Int? = null
    private var rendition: RenditionAdapter? = null
    private var contents = emptyList<Pair<String,String>>()
    private var openMs = 0L
    private var lastPrepareMs = 0L
    private var documentCacheHit = false
    private var speechNavigationSerial=0
    internal suspend fun highlightForTests(phrase:Phrase?)=highlightPhrase(phrase)
    internal fun readerSessionForTests() = session
    internal fun commentEditorVisibleForTests() = editor?.isShowing == true
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) importBook { store.stage(uri) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // A Readium Fragment needs its Publication before restoration. Rebuild from the durable
        // locator rather than asking FragmentManager to restore an unbound navigator.
        super.onCreate(null)
        readingTrace = ReadingTrace(java.io.File(filesDir,"reading-last-session.json"))
        readingTrace.record(ReadingTrace.Event.CREATE,ReadingTrace.Source.LIFECYCLE,activityId)
        store = BookStore(this); engine = ReadiumEngine(this); bridge = LocatorBridge(this)
        buildUi()
        player = PhrasePlayer(this, lifecycleScope, { highlightPhrase(it) }, { status.text = it })
        openingJob=lifecycleScope.launch {
            try {
                val previous = withContext(Dispatchers.IO) { store.current() }
                if (previous != null) load(previous) else status.text = "Откройте EPUB или пример. Это прототип P0."
            } catch (e: Exception) { handle(e) }
        }
    }
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF7F3EB.toInt()) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        heading = TextView(this).apply { text = "JJReader · P0"; textSize = 19f; setPadding(dp(16), dp(10), dp(12), dp(4)) }
        root.addView(heading)
        root.addView(row(button("Открыть EPUB") { if (!busy) openDocument.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
            button("Пример P0") { if (!busy) importBook { store.stage(assets.open("p0-sample.epub")) } }))
        notesButton = button("Пометки · 0") { showNotes() }
        root.addView(row(button("А−") { resize(-0.15) }, button("А+") { resize(0.15) }, notesButton))
        progress = TextView(this).apply { text = "Процент по видимому тексту · эксперимент"; textSize = 12f; setPadding(dp(16), 0, dp(12), dp(5)) }
        root.addView(progress)
        root.addView(FragmentContainerView(this).apply { id = READER_ID }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row(button("← Раздел") { changeResource(-1) }, button("Раздел →") { changeResource(1) }))
        val voices = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; setPadding(dp(12), 0, 0, 0) }
        Voice.entries.forEachIndexed { i, v -> voices.addView(RadioButton(this).apply { id = 100 + i; text = v.label; isChecked = v == voice }) }
        voices.setOnCheckedChangeListener { _, checked -> player.reset(); voice = if (checked == 100) Voice.DMITRY else Voice.SVETLANA }
        root.addView(voices)
        root.addView(row(button("5 фраз") { requestSpeech() }, button("Пауза") { player.pause() }, button("Продолжить") { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) player.resume() }))
        status = TextView(this).apply { textSize = 12f; setPadding(dp(16), dp(3), dp(12), dp(8)); maxLines = 3 }
        root.addView(status)
        root.addView(row(button("Диагностика TTS") { showTtsDiagnostics() }))
        setContentView(root)
    }
    internal fun readingTraceForTests() = readingTrace.json()
    private fun buildMetadata():JSONObject {
        val info=packageManager.getPackageInfo(packageName,0)
        val latest=openingAttempts.json().optJSONObject("latest")
        return JSONObject().put("versionName",info.versionName).put("versionCode",info.longVersionCode)
            .put("mode",latest?.optString("outcome")?.takeIf{it!="success"} ?: rendition?.mode ?: "idle").put("activeMode",rendition?.mode ?: JSONObject.NULL).put("converter",latest?.opt("converter") ?: JSONObject.NULL)
            .put("generation",latest?.opt("generation") ?: JSONObject.NULL).put("sourceModel",1).put("readingSchema",3)
            .put("runtime","Android WebView").put("webView",android.webkit.WebView.getCurrentWebViewPackage()?.versionName ?: "unknown")
            .put("prepareMs",latest?.opt("prepareMs") ?: JSONObject.NULL).put("cacheHit",latest?.opt("cacheHit") ?: JSONObject.NULL).put("openMs",if(latest?.optString("outcome")=="success" && openMs>0)openMs else JSONObject.NULL)
    }
    private fun combinedDiagnostics(): String = JSONObject().put("opening",openingAttempts.json()).put("build",buildMetadata()).put("reader",readingTrace.json())
        .put("tts",runCatching{JSONObject(player.diagnosticText())}.getOrElse{JSONObject().put("available",false)}).toString(2)
    private fun setUnified(enabled:Boolean) {
        getSharedPreferences("reader",MODE_PRIVATE).edit().putBoolean("unified",enabled).apply()
    }
    private fun showDiagnosticActions() {
        AlertDialog.Builder(this).setTitle("Проверки P0").setItems(arrayOf("Сравнить маршруты: 4 сетевых запроса", "Начать новую трассу чтения", "Оглавление / разделы", "Режим: единая лента / прежний")) { _, which ->
            if(which==0) AlertDialog.Builder(this).setTitle("Сравнение голосов/маршрутов")
                .setMessage("4 запроса Microsoft: два голоса × два маршрута, последовательно, без кеша, prefetch и повторов. Только фраза «${VoiceProbe.TEXT}». Обычный маршрут не меняется. После проверки откройте диагностику и скопируйте общий результат.")
                .setPositiveButton("Запустить"){_,_->player.checkVoices(compareRoutes=true)}.setNegativeButton("Отмена",null).show()
            else if(which==2) showContents()
            else if(which==3) AlertDialog.Builder(this).setTitle("Режим чтения").setItems(arrayOf("Единая лента","Прежний режим по разделам")){_,mode->
                val previous=openingJob
                lifecycleScope.launch {
                    previous?.cancelAndJoin()
                    player.reset();session?.capture()
                    setUnified(mode==0)
                    val current=withContext(Dispatchers.IO){store.current()}
                    if(current!=null)load(current)
                }
            }.setNegativeButton("Отмена",null).show()
            else { readingTrace.begin();readingTrace.metadata(buildMetadata()); readingTrace.record(ReadingTrace.Event.CREATE,ReadingTrace.Source.DIAGNOSTIC,activityId); session?.traceState(ReadingTrace.Event.INITIAL,ReadingTrace.Source.DIAGNOSTIC); status.text="Трасса начата. После сценария скопируйте диагностику." }
        }.setNegativeButton("Закрыть",null).show()
    }
    private fun traceDialog(source:ReadingTrace.Source,reason:ReadingTrace.Reason) {
        readingTrace.record(ReadingTrace.Event.DIALOG,source,activityId,session?.id,book?.id,observed=session?.chapterIndex(),ready=session?.ready,reason=reason)
    }
    private fun showTtsDiagnostics() {
        traceDialog(ReadingTrace.Source.DIAGNOSTIC,ReadingTrace.Reason.OPEN)
        val dialog = AlertDialog.Builder(this).setTitle("Диагностика Edge TTS")
            .setMessage(combinedDiagnostics())
            .setPositiveButton("Копировать") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                    android.content.ClipData.newPlainText("JJReader P0", combinedDiagnostics()))
            }.setNeutralButton("Проверки", null).setNegativeButton("Закрыть", null).create()
        var update: Job? = null
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showDiagnosticActions() }
            update = lifecycleScope.launch {
                while (dialog.isShowing) {
                    dialog.setMessage(combinedDiagnostics())
                    delay(300)
                }
            }
        }
        dialog.setOnDismissListener { update?.cancel(); traceDialog(ReadingTrace.Source.DIAGNOSTIC,ReadingTrace.Reason.CLOSE) }
        dialog.show()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun button(label: String, click: () -> Unit) = Button(this).apply { text = label; textSize = 12f; isAllCaps = false; minWidth = 0; setOnClickListener { click() } }
    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0)
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }
    private fun importBook(copy: () -> java.io.File) {
        player.pause(); busy = true; status.text = "Импорт внутренней копии EPUB…"
        openingJob=lifecycleScope.launch {
            var staged: java.io.File? = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    val file = copy().also { staged = it }
                    val candidate = engine.open(file)
                    try { store.commit(file, candidate.metadata.title ?: "Без названия", engine.chapters(candidate)) }
                    finally { candidate.close() }
                }
                load(imported)
            } catch (e: Exception) { handle(e) }
            finally { staged?.delete(); busy = false }
        }
    }
    private suspend fun load(value: Book) {
        openingJob=currentCoroutineContext()[Job]
        val unifiedEnabled=getSharedPreferences("reader",MODE_PRIVATE).getBoolean("unified",true)
        val info=packageManager.getPackageInfo(packageName,0)
        val attempt=openingAttempts.begin(info.versionName ?: "unknown",info.longVersionCode,unifiedEnabled,rendition?.mode)
        try {
        val newNotes = withContext(Dispatchers.IO) { NoteStore(this@MainActivity, value.id) }
        fun indexOf(href:String?) = value.chapters.indexOfFirst { it.href.substringBefore('#') == href?.substringBefore('#') }
        fun storeEvent(event:ReadingTrace.Event,source:ReadingTrace.Source,data:JSONObject,disk:JSONObject?) {
            val pos=data.optJSONObject("positionV2");val read=disk?.optJSONObject("positionV2")
            readingTrace.record(event,source,activityId,session?.takeIf{it.book.id==value.id}?.id,value.id,
                target=pos?.optInt("chapter"),start=pos?.optInt("start"),end=pos?.optInt("end"),
                diskChapter=read?.optInt("chapter"),diskStart=read?.optInt("start"),
                locatorChapter=indexOf(disk?.optJSONObject("locator")?.optString("href")),store=newNotes.instanceId,diskBook=read?.optString("bookId"),
                reason=if(pos?.optInt("chapter")!=read?.optInt("chapter") || pos?.optInt("start")!=read?.optInt("start"))ReadingTrace.Reason.WRITE_READ_MISMATCH else null)
        }
        newNotes.onSaved = { source,data,disk -> storeEvent(ReadingTrace.Event.STORE_WRITE,ReadingTrace.Source.valueOf(source.name),data,disk) }
        storeEvent(ReadingTrace.Event.STORE_LOAD,ReadingTrace.Source.INITIAL,newNotes.data,newNotes.readDisk())
        val openBegan=System.nanoTime()
        status.text=if(unifiedEnabled)"Подготовка единой ленты…"else"Открытие разделов…"
        val prepared: Pair<Publication,UnifiedDocument?>
        var pendingPublication:Publication?=null
        var preparingContents=emptyList<Pair<String,String>>()
        try {
            prepared=withContext(Dispatchers.IO) {
                val source=engine.open(value.file)
                fun flatten(items:List<org.readium.r2.shared.publication.Link>):List<Pair<String,String>> = items.flatMap{listOf((it.title ?: "Раздел") to it.url().toString())+flatten(it.children)}
                preparingContents=flatten(source.tableOfContents)
                if(!unifiedEnabled){pendingPublication=source;source to null} else try {
                    openingAttempts.stage(attempt,OpeningAttempt.Stage.ORDER_CHECK)
                    check(source.readingOrder.map{it.url().toString()}==value.chapters.map{it.href}) {"SOURCE_ORDER"}
                    val derived=UnifiedDocument.prepare(value,java.io.File(filesDir,"renditions-v1"),preparingContents,{openingAttempts.stage(attempt,it)},{openingAttempts.provider(attempt,it)})
                    openingAttempts.derived(attempt,derived)
                    openingAttempts.stage(attempt,OpeningAttempt.Stage.DERIVED_OPEN)
                    engine.open(derived.file).also{pendingPublication=it} to derived
                }finally{source.close()}
            }
        } catch(e:Exception) {pendingPublication?.close();throw e}
        currentCoroutineContext().ensureActive()
        check(openingAttempts.isCurrent(attempt)){"STALE_OPEN"}
        openingAttempts.stage(attempt,OpeningAttempt.Stage.NAVIGATOR_INIT)
        val (pub,derived)=prepared
        pendingPublication=null;contents=preparingContents
        val adapter=RenditionAdapter(value,derived)
        lastPrepareMs=derived?.prepareMs ?: 0;documentCacheHit=derived?.cacheHit ?: false
        session?.freeze()
        navigationJob?.cancelAndJoin(); resizeJob?.cancelAndJoin(); readerJob?.cancelAndJoin();decorationJob?.cancelAndJoin(); player.reset()
        navigator?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
        publication?.close()
        book = value; notes = newNotes; publication = pub; rendition=adapter; verifiedHref = null; mappingValid = false
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
            initialLocator = adapter.initialLocator(newNotes.data),
            initialPreferences = preferences(),
            configuration = EpubNavigatorFragment.Configuration {
                shouldApplyInsetsPadding = false; selectionActionModeCallback = selectionCallback
                decorationTemplates = ReaderDecorations.templates()
            })
        val nav = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        navigator = nav
        val reader = ReaderSession(value,newNotes,adapter.port(nav,bridge){index->nav.go(pub.readingOrder[index],animated=false)},readingTrace,activityId){player.reset();speechChapter=null}
        session = reader
        speechChapter = null
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        supportFragmentManager.beginTransaction().add(READER_ID, nav, "reader").commitNow()
        nav.addDecorationListener("notes", object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                player.pause()
                val id = event.decoration.id.removeSuffix("-icon")
                newNotes.notes.firstOrNull { it.getString("id") == id }?.let { note ->
                    AlertDialog.Builder(this@MainActivity).setTitle("Пробная пометка").setMessage(note.getString("quote") + "\n\n" + note.getString("comment")).setPositiveButton("Закрыть", null).show()
                }
                return true
            }
        })
        heading.text = value.title
        status.text = if(derived!=null)"Единая лента. Долгое нажатие → Пометка. Оглавление — в диагностике."else"Прежний режим по разделам. Долгое нажатие → Пометка."
        openingAttempts.success(attempt,adapter.mode)
        openMs=0
        readingTrace.metadata(buildMetadata())
        if(value.total==0){progress.text="0% · в книге нет читаемого текста";return}
        readerJob = lifecycleScope.launch {
            var draftShown = false
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    try { if (reader.shouldRestoreInitial && editor == null) reader.restoreInitial() }
                    catch(e: Exception) { handle(e) } // Keep observation/capture siblings alive after an initial ACK failure.
                }
                launch {
                    nav.currentLocator.collect { locator ->
                        if (verifiedHref != locator.href.toString()) { mappingValid = false; verifiedHref = null }
                        // Engine start/progression events are observations, never durable anchors.
                        newNotes.data.put("engineLocator", locator.toJSON())
                        reader.traceState(ReadingTrace.Event.NATIVE,ReadingTrace.Source.NATIVE)
                        reader.observeResource()
                        if (adapter.unified==null && speechChapter != null && speechChapter != reader.chapterIndex()) { player.reset(); speechChapter = null }
                    }
                }
                launch {
                    while (isActive) {
                        try { updateViewport(nav) } catch (e: Exception) { if (e is CancellationException && e !is StaleReaderOperation) throw e; progress.text = "Процент: DOM ещё не готов" }
                        if(!draftShown && reader.ready) {
                            draftShown=true
                            openMs=(System.nanoTime()-openBegan)/1_000_000;readingTrace.metadata(buildMetadata())
                            refreshNotes();newNotes.data.optJSONObject("draft")?.let{editNote(it)}
                        }
                        delay(350)
                    }
                }
            }
        }
        } catch(e:Exception) {
            openingAttempts.failed(attempt,e)
            if(e is CancellationException)throw e
            if(!openingAttempts.isCurrent(attempt))return
            val failed=openingAttempts.json().getJSONObject("latest")
            status.text="Ошибка открытия: ${failed.getString("stage")} (${failed.getString("errorCode")}). Источник и пометки сохранены."
            AlertDialog.Builder(this).setTitle("Книга не открыта").setMessage(status.text)
                .setPositiveButton("Прежний режим"){_,_->setUnified(false);openingJob=lifecycleScope.launch{load(value)}}
                .setNegativeButton("Закрыть",null).show()
        }
    }
    private fun preferences() = EpubPreferences(scroll = true, fontSize = notes?.data?.optDouble("fontSize", 1.0) ?: 1.0, pageMargins = 2.0, publisherStyles = false)
    private fun resize(delta: Double) {
        val data = notes?.data ?: return; player.pause()
        data.put("fontSize", (data.optDouble("fontSize", 1.0) + delta).coerceIn(0.7, 2.8)); persist()
        resizeJob?.cancel()
        val reader = session ?: return
        val anchor = data.optJSONObject("positionV2")?.let { JSONObject(it.toString()) }
        val operation = reader.freeze(ReadingTrace.Source.FONT)
        val scale = data.getDouble("fontSize")
        // Applying the preference must not depend on a viewport/locator operation succeeding.
        navigator?.submitPreferences(preferences())
        status.text = "Размер: ${(scale * 100).toInt()}%"
        resizeJob = lifecycleScope.launch {
            try {
                reader.awaitFont(scale, operation)
                if (anchor != null) reader.restore(anchor, operation = operation) else reader.restoreInitial()
            } catch (e: Exception) { handle(e) }
        }
    }
    private fun chapterIndex(nav: EpubNavigatorFragment) = session?.chapterIndex() ?: -1
    private fun changeResource(delta: Int) {
        val current=book ?: return;val index=(session?.chapterIndex() ?: 0)+delta
        if(index !in current.chapters.indices)return
        if(rendition?.unified!=null)jumpSource(index) else {
            player.reset();speechChapter=null;navigationJob?.cancel();resizeJob?.cancel();session?.userNavigation()
            navigator?.go(checkNotNull(publication).readingOrder[index],animated=false)
        }
    }
    private fun jumpSource(index:Int,targetId:String?=null) {
        val adapter=rendition ?: return;val nav=navigator ?: return;val reader=session ?: return
        player.reset();navigationJob?.cancel();resizeJob?.cancel()
        navigationJob=lifecycleScope.launch {
            try {
                if(adapter.unified==null){
                    reader.userNavigation()
                    val link=if(targetId==null)checkNotNull(publication).readingOrder[index] else checkNotNull(org.readium.r2.shared.publication.Link.fromJSON(JSONObject().put("href",targetId).put("type","application/xhtml+xml")))
                    nav.go(link,animated=false);return@launch
                }
                val (actual,offset)=adapter.boundary(index) ?: error("В книге нет текста для перехода")
                var target=targetId?.let{bridge.call(nav,"JJ.forSection($actual).anchorForId(${JSONObject.quote(it)})")}
                if(target==null)target=adapter.anchor(bridge,nav,actual,offset,offset+1)
                target.put("chapter",actual);reader.navigateTo(target)
            }catch(e:Exception){handle(e)}
        }
    }
    private fun showContents() {
        val adapter=rendition ?: return
        val toc=adapter.unified?.map?.getJSONArray("toc")
        if(toc!=null)AlertDialog.Builder(this).setTitle(if(adapter.unified.map.getBoolean("authorToc"))"Оглавление"else"Разделы — оглавление отсутствует")
            .setItems((0 until toc.length()).map{toc.getJSONObject(it).getString("title")}.toTypedArray()){_,which->val item=toc.getJSONObject(which);jumpSource(item.getInt("chapter"),item.getString("target"))}.setNegativeButton("Закрыть",null).show()
        else AlertDialog.Builder(this).setTitle(if(contents.isNotEmpty())"Оглавление"else"Разделы — оглавление отсутствует")
            .setItems((if(contents.isNotEmpty())contents.map{it.first}else adapter.book.chapters.map{it.title}).toTypedArray()){_,which->
                val index=if(contents.isNotEmpty())adapter.book.chapters.indexOfFirst{it.href.substringBefore('#')==contents[which].second.substringBefore('#')}else which
                if(index>=0)jumpSource(index,contents.getOrNull(which)?.second)
            }.setNegativeButton("Закрыть",null).show()
    }
    private suspend fun updateViewport(nav: EpubNavigatorFragment) {
        val current = book ?: return; val index = chapterIndex(nav); if (index < 0) return
        val reader = session ?: return
        val viewport = reader.capture() ?: return
        mappingValid = true; verifiedHref = nav.currentLocator.value.href.toString()
        val end = viewport.getInt("last"); visibleFirst = (viewport.optJSONObject("anchor")?.optInt("start") ?: viewport.getInt("first")).coerceAtLeast(0); if (end < 0) return
        progress.text = "${TextModel.percent(checkNotNull(rendition).absolute(viewport.optInt("lastSourceChapter",index),end),current.total)}% · эксперимент · ресурс ${index + 1}/${current.chapters.size}"
    }
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            player.pause()
            val id = UUID.randomUUID().toString(); selectionSession = id
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { navigator?.let { bridge.call(it, "JJ.beginSelection(${JSONObject.quote(id)})") } }
            menu.add(0, 501, 0, "Пометка"); return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != 501) return false
            val reader = session ?: return true
            val id = selectionSession ?: return true
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val anchor = reader.selection(id)
                    check(session === reader && selectionSession == id) { "SELECTION_STALE_BOOK" }
                    anchor.put("id", UUID.randomUUID().toString()).put("comment", "")
                    notes?.data?.put("draft", anchor); persist(); mode.finish(); editNote(anchor)
                } catch (e: Exception) { handle(e) }
            }; return true
        }
        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
    private fun editNote(draft: JSONObject) {
        traceDialog(ReadingTrace.Source.IME,ReadingTrace.Reason.OPEN)
        player.pause()
        val reader = session
        val returnPosition = notes?.data?.optJSONObject("positionV2")?.let { JSONObject(it.toString()) }
        val operation = reader?.freeze(ReadingTrace.Source.IME) // IME resizing must never replace the durable reading position.
        val field = EditText(this).apply { hint = "Короткий комментарий"; setText(draft.optString("comment")); minLines = 2; setSelection(text.length) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@MainActivity).apply { text = draft.getString("quote"); maxLines = 5 }); addView(field)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft.put("comment", s.toString()); notes?.data?.put("draft", draft); persist() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val dialog = AlertDialog.Builder(this).setTitle("Пробная пометка · жёлтый").setView(content).setPositiveButton("Сохранить", null)
            .setNegativeButton("Отменить") { _, _ -> notes?.data?.remove("draft"); persist() }.create()
        editor = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (field.text.isBlank()) { field.error = "Введите комментарий"; return@setOnClickListener }
                try { notes?.add(draft); dialog.dismiss(); navigator?.clearSelection(); refreshNotes() } catch (e: Exception) { handle(e) }
            }
            field.requestFocus(); dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            field.post { getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
        }
        dialog.setOnDismissListener {
            traceDialog(ReadingTrace.Source.IME,ReadingTrace.Reason.CLOSE)
            editor = null
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
            lifecycleScope.launch {
                try {
                    try { withTimeout(3000) { while (ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true) delay(32) } }
                    catch (_: TimeoutCancellationException) { }
                    if (session === reader && reader != null) {
                        reader.requireCurrent(checkNotNull(operation))
                        if (returnPosition != null) reader.restore(returnPosition, operation = checkNotNull(operation)) else reader.restoreInitial()
                        reader.capture()
                    }
                } catch (e: Exception) { handle(e) }
            }
        }
        dialog.show()
    }
    private fun refreshNotes() {
        val list = notes?.notes?.sortedWith(compareBy({it.optInt("chapter")},{it.optInt("start")})) ?: emptyList(); notesButton.text = "Пометки · ${list.size}"
        val nav=navigator ?: return;val adapter=rendition ?: return;val reader=session ?: return
        decorationJob?.cancel()
        decorationJob=lifecycleScope.launch {
            val decorations=list.flatMap { note ->
                val locator = try {
                    if(adapter.unified!=null)adapter.displayLocator(bridge,nav,note)
                    else Locator.fromJSON(note.getJSONObject("locator")) ?: return@flatMap emptyList()
                }catch(e:Exception){if(e is CancellationException)throw e;status.text="Некоторые пометки недоступны в единой ленте. Данные сохранены; доступен прежний режим.";return@flatMap emptyList()}
                listOf(Decoration(note.getString("id"), locator, Decoration.Style.Highlight(0xFFFFD85A.toInt())), Decoration(note.getString("id") + "-icon", locator, MarginMark()))
            }
            currentCoroutineContext().ensureActive();if(session===reader)nav.applyDecorations(decorations,"notes")
        }
    }
    private fun showNotes() {
        player.pause()
        traceDialog(ReadingTrace.Source.NOTE,ReadingTrace.Reason.OPEN)
        val list = notes?.notes?.sortedWith(compareBy({ it.getInt("chapter") }, { it.getInt("start") }, { it.getString("id") })) ?: emptyList()
        if (list.isEmpty()) { status.text = "Выделите текст долгим нажатием → Пометка."; return }
        val listDialog = AlertDialog.Builder(this).setTitle("Пробные пометки").setItems(list.map { it.getString("quote").take(70) + " — " + it.getString("comment") }.toTypedArray()) { _, which ->
            val reader = session ?: return@setItems
            player.reset();speechChapter=null
            val note = JSONObject(list[which].toString())
            navigationJob?.cancel(); resizeJob?.cancel()
            navigationJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    reader.navigateTo(note)
                    if(reader.lastRestoreCode!=null)status.text="Сохранено проверенное видимое место. Точный диапазон не подтверждён; доступна диагностика."
                } catch (e: Exception) {
                    readingTrace.record(ReadingTrace.Event.REJECT,ReadingTrace.Source.NOTE,activityId,reader.id,reader.book.id,
                        target=note.optInt("chapter"),observed=reader.chapterIndex(),ready=reader.ready,
                        reason=if(e is CancellationException)ReadingTrace.Reason.CANCELLED else ReadingTrace.Reason.ERROR,
                        failureCode=(e as? RestoreFailure)?.code)
                    handle(e)
                }
            }
        }.setNegativeButton("Закрыть", null).show()
        listDialog.setOnDismissListener { traceDialog(ReadingTrace.Source.NOTE,ReadingTrace.Reason.CLOSE) }
    }
    private fun requestSpeech() {
        if (navigator == null) return
        val consent = getSharedPreferences("privacy", MODE_PRIVATE)
        if (!consent.getBoolean("edge", false)) {
            AlertDialog.Builder(this).setTitle("Сетевая озвучка Edge")
                .setMessage("Пробные фразы и одна следующая фраза отправляются Microsoft. Нужен интернет. Комментарии не отправляются.")
                .setPositiveButton("Включить") { _, _ -> consent.edit().putBoolean("edge", true).commit(); startSpeech() }.setNegativeButton("Отмена", null).show()
        } else startSpeech()
    }
    private fun startSpeech() {
        lifecycleScope.launch {
            try {
                val nav=navigator ?: return@launch;val reader=session ?: return@launch;val adapter=rendition ?: return@launch
                check(reader.ready){"Дождитесь восстановления места чтения"}
                val operation=reader.currentRevision
                updateViewport(nav)
                val source=reader.chapterIndex();val offset=visibleFirst
                val phrases=withContext(Dispatchers.Default){adapter.phrases(source,offset).filter{adapter.unified!=null || it.chapter==source}}
                currentCoroutineContext().ensureActive();reader.requireCurrent(operation)
                check(session===reader && rendition===adapter){"STALE_READER_OPERATION"}
                val state=reader.context(source)
                currentCoroutineContext().ensureActive();reader.requireCurrent(operation)
                speechChapter=source;speechNavigationSerial=state.optInt("navigationSerial")
                player.start(phrases,voice)
            }catch(e:Exception){handle(e)}
        }
    }
    private suspend fun highlightPhrase(phrase: Phrase?) {
        val nav = navigator ?: return
        if (phrase == null) { nav.applyDecorations(emptyList(), "tts"); return }
        val reader = session ?: return
        val chapter=phrase.chapter.takeIf{it>=0} ?: chapterIndex(nav)
        val operation=reader.currentRevision
        reader.context(chapter)
        val anchor=checkNotNull(rendition).anchor(bridge,nav,chapter,phrase.start,phrase.end)
        currentCoroutineContext().ensureActive();reader.requireCurrent(operation)
        reader.validate(anchor,chapter)
        val locator = bridge.locator(nav.currentLocator.value, anchor)
        nav.applyDecorations(listOf(Decoration("tts", locator, Decoration.Style.Underline(ReaderDecorations.BLUE))), "tts")
        currentCoroutineContext().ensureActive();reader.requireCurrent(operation)
        val scope=if(rendition?.unified!=null)"JJ.forSection($chapter)"else"JJ"
        val moved=bridge.call(nav,"({ok:$scope.go(${phrase.start},${phrase.end},$speechNavigationSerial)})")
        if(moved?.optBoolean("ok")!=true)player.pause()
    }
    private fun persist() { try { notes?.save(NoteStore.Source.ACTIVITY) } catch (e: Exception) { handle(e) } }
    private fun handle(e: Exception) {
        if (e is StaleReaderOperation) return
        if (e is RestoreFailure) {
            status.text="Место не подтверждено (${e.code}). Проверяется текущий видимый текст; доступна диагностика.";return
        }
        if (e is CancellationException) throw e
        status.text = e.message?.takeIf { !it.contains("<") && it.length < 180 } ?: "Не удалось выполнить действие. Данные книги сохранены."
    }
    override fun onResume() { super.onResume(); if (navigator != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    override fun onPause() {
        readingTrace.record(ReadingTrace.Event.PAUSE,ReadingTrace.Source.LIFECYCLE,activityId)
        session?.traceState(ReadingTrace.Event.PAUSE,ReadingTrace.Source.LIFECYCLE)
        player.pause()
        val reader=session
        reader?.preparePause()
        navigationJob?.cancel()
        resizeJob?.cancel()
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if(reader?.capture()==null || !reader.ready) {
                    reader?.traceFallback()
                    status.text="Сохранено последнее подтверждённое место; текущий снимок недоступен. Диагностика содержит причину."
                }
            } catch(e: Exception) {
                reader?.traceFallback()
                if(e is CancellationException)throw e
            }
        }
        // capture commits itself. Do not rewrite an old anchor as though it were the paused view.
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onPause()
    }
    override fun onDestroy() { readingTrace.record(ReadingTrace.Event.DESTROY,ReadingTrace.Source.LIFECYCLE,activityId); session?.traceState(ReadingTrace.Event.DESTROY,ReadingTrace.Source.LIFECYCLE); session?.freeze(); editor?.dismiss(); resizeJob?.cancel(); readerJob?.cancel(); player.close(); super.onDestroy(); publication?.close() }
    companion object { private const val READER_ID = 23001 }
}
