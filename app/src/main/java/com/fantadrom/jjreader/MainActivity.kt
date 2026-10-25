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
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.util.UUID

class MarginMark() : Decoration.Style {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<MarginMark> {
            override fun createFromParcel(source: Parcel) = MarginMark()
            override fun newArray(size: Int): Array<MarginMark?> = arrayOfNulls(size)
        }
    }
}

/** P0 laboratory. The original Compose theme is retained for the next stage. */
class MainActivity : AppCompatActivity() {
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
    private var busy = false
    private var voice = Voice.DMITRY
    private var verifiedHref: String? = null
    private var mappingValid = false
    private var visibleFirst = 0
    private var editor: AlertDialog? = null
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
        root.addView(status); setContentView(root)
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
        resizeJob?.cancelAndJoin(); readerJob?.cancelAndJoin(); player.reset()
        navigator?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
        publication?.close()
        book = value; notes = newNotes; publication = pub; verifiedHref = null; mappingValid = false
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
            initialLocator = newNotes.data.optJSONObject("locator")?.let { Locator.fromJSON(it) },
            initialPreferences = preferences(),
            configuration = EpubNavigatorFragment.Configuration {
                shouldApplyInsetsPadding = false; selectionActionModeCallback = selectionCallback
                decorationTemplates[MarginMark::class] = HtmlDecorationTemplate(
                    layout = HtmlDecorationTemplate.Layout.BOUNDS, width = HtmlDecorationTemplate.Width.PAGE,
                    element = { "<div><span data-activable='1' class='jj-note' role='button' aria-label='Комментарий'>●</span></div>" },
                    stylesheet = ".jj-note{position:absolute;left:3px;top:0;width:20px;height:24px;color:#8a4800!important;font-size:18px!important;line-height:24px!important;}"
                )
            })
        val nav = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        navigator = nav
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
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    nav.currentLocator.collect { locator ->
                        if (verifiedHref != locator.href.toString()) { mappingValid = false; verifiedHref = null }
                        newNotes.data.put("locator", locator.toJSON()); persist()
                    }
                }
                launch {
                    while (isActive) {
                        try { updateViewport(nav) } catch (e: Exception) { if (e is CancellationException) throw e; progress.text = "Процент: DOM ещё не готов" }
                        delay(750)
                    }
                }
            }
        }
        newNotes.data.optJSONObject("draft")?.let { editNote(it) }
    }
    private fun preferences() = EpubPreferences(scroll = true, fontSize = notes?.data?.optDouble("fontSize", 1.0) ?: 1.0, pageMargins = 2.0, publisherStyles = false)
    private fun resize(delta: Double) {
        val data = notes?.data ?: return; player.pause()
        data.put("fontSize", (data.optDouble("fontSize", 1.0) + delta).coerceIn(0.7, 2.8)); persist()
        resizeJob?.cancel()
        resizeJob = lifecycleScope.launch {
            try {
                val nav = navigator ?: return@launch
                updateViewport(nav)
                val anchor = data.optJSONObject("locator")?.let { Locator.fromJSON(it) }
                nav.submitPreferences(preferences())
                delay(300)
                if (anchor != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) nav.go(anchor, animated = false)
            } catch (e: Exception) { handle(e) }
        }
    }
    private fun chapterIndex(nav: EpubNavigatorFragment) = book?.chapters?.indexOfFirst { it.href.substringBefore('#') == nav.currentLocator.value.href.toString().substringBefore('#') } ?: -1
    private fun changeResource(delta: Int) {
        val nav = navigator ?: return; val pub = publication ?: return
        player.pause(); val index = chapterIndex(nav) + delta
        if (index in pub.readingOrder.indices) nav.go(pub.readingOrder[index], animated = false)
    }
    private suspend fun updateViewport(nav: EpubNavigatorFragment) {
        val current = book ?: return; val index = chapterIndex(nav); if (index < 0) return
        val href = nav.currentLocator.value.href.toString()
        if (verifiedHref != href) {
            mappingValid = bridge.text(nav) == current.chapters[index].text; verifiedHref = href
            if (!mappingValid) status.text = "DOM и модель различаются: точный процент и новые пометки заблокированы."
        }
        if (!mappingValid) { progress.text = "Точный процент недоступен · несовпадение модели"; return }
        val viewport = bridge.call(nav, "JJ.visible()") ?: return
        val end = viewport.getInt("last"); visibleFirst = viewport.getInt("first").coerceAtLeast(0); if (end < 0) return
        progress.text = "${TextModel.percent(current.offset(index) + end, current.total)}% · эксперимент · ресурс ${index + 1}/${current.chapters.size}"
        bridge.call(nav, "JJ.anchor($visibleFirst,${visibleFirst + 1})")?.let {
            notes?.data?.put("locator", bridge.locator(nav.currentLocator.value, it).toJSON()); persist()
        }
    }
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { player.pause(); menu.add(0, 501, 0, "Пометка"); return true }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != 501) return false
            lifecycleScope.launch {
                try {
                    val nav = navigator ?: return@launch; updateViewport(nav); if (!mappingValid) return@launch
                    val anchor = bridge.call(nav, "JJ.selection()") ?: error("Выделите текст внутри одного ресурса")
                    check(nav.currentSelection() != null) { "Readium не вернул выделение" }
                    val index = chapterIndex(nav)
                    check(TextModel.slice(book!!.chapters[index].text, anchor.getInt("start"), anchor.getInt("end")) == anchor.getString("quote"))
                    anchor.put("locator", bridge.locator(nav.currentLocator.value, anchor).toJSON()).put("chapter", index)
                        .put("id", UUID.randomUUID().toString()).put("textHash", TextModel.hash(book!!.chapters[index].text.toByteArray())).put("comment", "")
                    notes?.data?.put("draft", anchor); persist(); mode.finish(); editNote(anchor)
                } catch (e: Exception) { handle(e) }
            }; return true
        }
        override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
    private fun editNote(draft: JSONObject) {
        player.pause()
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
        dialog.setOnDismissListener { editor = null }; dialog.show()
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
            val note = list[which]
            lifecycleScope.launch {
                try {
                    val nav = navigator ?: return@launch; val chapter = book!!.chapters[note.getInt("chapter")]
                    check(TextModel.hash(chapter.text.toByteArray()) == note.getString("textHash")) { "Текст не соответствует локатору" }
                    check(TextModel.slice(chapter.text, note.getInt("start"), note.getInt("end")) == note.getString("quote"))
                    val target = Locator.fromJSON(note.getJSONObject("locator")) ?: error("Локатор повреждён")
                    check(nav.go(target, animated = false)) { "Переход к локатору не принят" }
                    withTimeout(10000) {
                        while (chapterIndex(nav) != note.getInt("chapter")) delay(100)
                        check(bridge.text(nav) == chapter.text) { "DOM не соответствует сохранённому тексту" }
                        bridge.call(nav, "({ok:JJ.go(${note.getInt("start")},${note.getInt("end")})})")
                    }
                } catch (e: Exception) { handle(e) }
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
                val nav = navigator ?: return@launch; updateViewport(nav); if (!mappingValid) return@launch
                val chapter = book!!.chapters[chapterIndex(nav)]
                val phrases = withContext(Dispatchers.Default) { Phrases.split(chapter.text) }.dropWhile { it.end <= visibleFirst }
                player.start(phrases, voice)
            } catch (e: Exception) { handle(e) }
        }
    }
    private suspend fun highlightPhrase(phrase: Phrase) {
        val nav = navigator ?: return; val anchor = bridge.call(nav, "JJ.anchor(${phrase.start},${phrase.end})") ?: return
        val locator = bridge.locator(nav.currentLocator.value, anchor)
        nav.applyDecorations(listOf(Decoration("tts", locator, Decoration.Style.Underline(0xFF2267D0.toInt()))), "tts")
        bridge.call(nav, "({ok:JJ.go(${phrase.start},${phrase.end})})")
    }
    private fun persist() { try { notes?.save() } catch (e: Exception) { handle(e) } }
    private fun handle(e: Exception) {
        if (e is CancellationException) throw e
        status.text = e.message?.takeIf { !it.contains("<") && it.length < 180 } ?: "Не удалось выполнить действие. Данные книги сохранены."
    }
    override fun onResume() { super.onResume(); if (navigator != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    override fun onPause() { player.pause(); persist(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onPause() }
    override fun onDestroy() { editor?.dismiss(); resizeJob?.cancel(); readerJob?.cancel(); player.close(); super.onDestroy(); publication?.close() }
    companion object { private const val READER_ID = 23001 }
}
