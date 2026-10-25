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
    internal fun readerSessionForTests() = session
    internal fun commentEditorVisibleForTests() = editor?.isShowing == true
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) importBook { store.stage(uri) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // A Readium Fragment needs its Publication before restoration. Rebuild from the durable
        // locator rather than asking FragmentManager to restore an unbound navigator.
        super.onCreate(null)
        store = BookStore(this); engine = ReadiumEngine(this); bridge = LocatorBridge(this)
        buildUi()
        player = PhrasePlayer(this, lifecycleScope, { highlightPhrase(it) }, { status.text = it })
        lifecycleScope.launch {
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
    private fun showTtsDiagnostics() {
        val dialog = AlertDialog.Builder(this).setTitle("Диагностика Edge TTS")
            .setMessage(player.diagnosticText())
            .setPositiveButton("Копировать") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                    android.content.ClipData.newPlainText("JJReader TTS", player.diagnosticText()))
            }.setNeutralButton("Проверить голоса", null).setNegativeButton("Закрыть", null).create()
        var update: Job? = null
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { player.checkVoices() }
            update = lifecycleScope.launch {
                while (dialog.isShowing) {
                    dialog.setMessage("Проверка отправляет Microsoft только фиксированную фразу «${VoiceProbe.TEXT}» для обоих голосов, последовательно, без кеша.\n\n" + player.diagnosticText())
                    delay(300)
                }
            }
        }
        dialog.setOnDismissListener { update?.cancel() }
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
        lifecycleScope.launch {
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
        val newNotes = withContext(Dispatchers.IO) { NoteStore(this@MainActivity, value.id) }
        val pub = withContext(Dispatchers.IO) { engine.open(value.file) }
        session?.freeze()
        navigationJob?.cancelAndJoin(); resizeJob?.cancelAndJoin(); readerJob?.cancelAndJoin(); player.reset()
        navigator?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
        publication?.close()
        book = value; notes = newNotes; publication = pub; verifiedHref = null; mappingValid = false
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
            initialLocator = newNotes.data.optJSONObject("locator")?.let { Locator.fromJSON(it) },
            initialPreferences = preferences(),
            configuration = EpubNavigatorFragment.Configuration {
                shouldApplyInsetsPadding = false; selectionActionModeCallback = selectionCallback
                decorationTemplates = ReaderDecorations.templates()
            })
        val nav = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        navigator = nav
        val reader = ReaderSession(value, newNotes, nav, bridge) { index -> nav.go(pub.readingOrder[index], animated = false) }
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
        heading.text = value.title; refreshNotes()
        status.text = "Прокрутка внутри раздела; для границы — ←/→. Долгое нажатие → Пометка."
        readerJob = lifecycleScope.launch {
            var draftShown = false
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try { if (!reader.ready && editor == null) reader.restoreInitial() }
                catch (e: Exception) { if (e !is StaleReaderOperation) { handle(e); return@repeatOnLifecycle } }
                if (!draftShown) {
                    draftShown = true
                    newNotes.data.optJSONObject("draft")?.let { editNote(it) }
                }
                launch {
                    nav.currentLocator.collect { locator ->
                        if (verifiedHref != locator.href.toString()) { mappingValid = false; verifiedHref = null }
                        // Engine start/progression events are observations, never durable anchors.
                        newNotes.data.put("engineLocator", locator.toJSON())
                        if (speechChapter != null && speechChapter != reader.chapterIndex()) { player.reset(); speechChapter = null }
                    }
                }
                launch {
                    while (isActive) {
                        try { updateViewport(nav) } catch (e: Exception) { if (e is CancellationException && e !is StaleReaderOperation) throw e; progress.text = "Процент: DOM ещё не готов" }
                        delay(350)
                    }
                }
            }
        }
    }
    private fun preferences() = EpubPreferences(scroll = true, fontSize = notes?.data?.optDouble("fontSize", 1.0) ?: 1.0, pageMargins = 2.0, publisherStyles = false)
    private fun resize(delta: Double) {
        val data = notes?.data ?: return; player.pause()
        data.put("fontSize", (data.optDouble("fontSize", 1.0) + delta).coerceIn(0.7, 2.8)); persist()
        resizeJob?.cancel()
        val reader = session ?: return
        val anchor = data.optJSONObject("positionV2")?.let { JSONObject(it.toString()) }
        val operation = reader.freeze()
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
    private fun chapterIndex(nav: EpubNavigatorFragment) = book?.chapters?.indexOfFirst { it.href.substringBefore('#') == nav.currentLocator.value.href.toString().substringBefore('#') } ?: -1
    private fun changeResource(delta: Int) {
        val nav = navigator ?: return; val pub = publication ?: return
        player.reset(); speechChapter = null; val index = chapterIndex(nav) + delta
        if (index in pub.readingOrder.indices) nav.go(pub.readingOrder[index], animated = false)
    }
    private suspend fun updateViewport(nav: EpubNavigatorFragment) {
        val current = book ?: return; val index = chapterIndex(nav); if (index < 0) return
        val reader = session ?: return
        val viewport = reader.capture() ?: return
        mappingValid = true; verifiedHref = nav.currentLocator.value.href.toString()
        val end = viewport.getInt("last"); visibleFirst = viewport.getInt("first").coerceAtLeast(0); if (end < 0) return
        progress.text = "${TextModel.percent(current.offset(index) + end, current.total)}% · эксперимент · ресурс ${index + 1}/${current.chapters.size}"
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
        player.pause()
        val reader = session
        val returnPosition = notes?.data?.optJSONObject("positionV2")?.let { JSONObject(it.toString()) }
        val operation = reader?.freeze() // IME resizing must never replace the durable reading position.
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
        val list = notes?.notes ?: emptyList(); notesButton.text = "Пометки · ${list.size}"
        lifecycleScope.launch {
            navigator?.applyDecorations(list.flatMap { note ->
                val locator = Locator.fromJSON(note.getJSONObject("locator")) ?: return@flatMap emptyList()
                listOf(Decoration(note.getString("id"), locator, Decoration.Style.Highlight(0xFFFFD85A.toInt())), Decoration(note.getString("id") + "-icon", locator, MarginMark()))
            }, "notes")
        }
    }
    private fun showNotes() {
        player.pause()
        val list = notes?.notes?.sortedWith(compareBy({ it.getInt("chapter") }, { it.getInt("start") }, { it.getString("id") })) ?: emptyList()
        if (list.isEmpty()) { status.text = "Выделите текст долгим нажатием → Пометка."; return }
        AlertDialog.Builder(this).setTitle("Пробные пометки").setItems(list.map { it.getString("quote").take(70) + " — " + it.getString("comment") }.toTypedArray()) { _, which ->
            val reader = session ?: return@setItems
            val note = JSONObject(list[which].toString())
            navigationJob?.cancel(); resizeJob?.cancel()
            navigationJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { reader.navigateTo(note) } catch (e: Exception) { handle(e) }
            }
        }.setNegativeButton("Закрыть", null).show()
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
                val nav = navigator ?: return@launch
                check(session?.ready == true) { "Дождитесь восстановления места чтения" }
                updateViewport(nav)
                val chapter = book!!.chapters[chapterIndex(nav)]
                val phrases = withContext(Dispatchers.Default) { Phrases.split(chapter.text) }.dropWhile { it.end <= visibleFirst }
                speechChapter = chapterIndex(nav)
                player.start(phrases, voice)
            } catch (e: Exception) { handle(e) }
        }
    }
    private suspend fun highlightPhrase(phrase: Phrase?) {
        val nav = navigator ?: return
        if (phrase == null) { nav.applyDecorations(emptyList(), "tts"); return }
        val reader = session ?: return
        reader.context()
        val anchor = bridge.call(nav, "JJ.anchor(${phrase.start},${phrase.end})") ?: error("TTS_ANCHOR")
        reader.validate(anchor, chapterIndex(nav))
        val locator = bridge.locator(nav.currentLocator.value, anchor)
        nav.applyDecorations(listOf(Decoration("tts", locator, Decoration.Style.Underline(ReaderDecorations.BLUE))), "tts")
        bridge.call(nav, "({ok:JJ.go(${phrase.start},${phrase.end})})")
    }
    private fun persist() { try { notes?.save() } catch (e: Exception) { handle(e) } }
    private fun handle(e: Exception) {
        if (e is StaleReaderOperation) return
        if (e is CancellationException) throw e
        status.text = e.message?.takeIf { !it.contains("<") && it.length < 180 } ?: "Не удалось выполнить действие. Данные книги сохранены."
    }
    override fun onResume() { super.onResume(); if (navigator != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    override fun onPause() {
        player.pause()
        navigationJob?.cancel()
        resizeJob?.cancel()
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { session?.capture() } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        persist(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onPause()
    }
    override fun onDestroy() { session?.freeze(); editor?.dismiss(); resizeJob?.cancel(); readerJob?.cancel(); player.close(); super.onDestroy(); publication?.close() }
    companion object { private const val READER_ID = 23001 }
}
