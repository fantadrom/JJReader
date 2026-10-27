@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.fantadrom.jjreader

import android.app.AlertDialog
import android.os.*
import android.text.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
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
import com.fantadrom.jjreader.ui.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import java.util.UUID
import kotlin.coroutines.resume

/** P1 shell and book lifetimes around the accepted stable Readium engine. */
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
    protected open val prototypeControls:Boolean get()=false
    private var shell:ReaderShell?=null
    private val appearance by lazy{getSharedPreferences("appearance",MODE_PRIVATE)}
    private val sourceFolder by lazy{SourceFolder(this)}
    private var shelfJob:Job?=null
    private var folderJob:Job?=null
    private var scrubJob:Job?=null
    private var scrubbing=false
    private var brightnessGesture:String?=null
    private var brightnessBase=.5f
    private var busy = false
    private var voice = Voice.DMITRY
    private var verifiedHref: String? = null
    private var mappingValid = false
    private var visibleFirst = 0
    private var editor: AlertDialog? = null
    private var session: ReaderSession? = null
    private var selectionSession: String? = null
    private var speechChapter: Int? = null
    private var sectionNavigation:SectionNavigation?=null
    private var rendition: RenditionAdapter? = null
    private var contents = emptyList<Pair<String,String>>()
    private var openMs = 0L
    private var lastPrepareMs = 0L
    private var documentCacheHit = false
    private var speechNavigationSerial=0
    private var selectionConfirmed=false
    protected open fun localSpeechAudio(phrase:Phrase):java.io.File?=null
    internal fun shelfForTests()=showShelf()
    internal fun panelsForTests()=togglePanels()
    internal fun seekForTests(value:Int){rememberPlace();seekWholeBook(value)}
    internal fun returnForTests()=returnToPlace()
    internal fun startSpeechForTests()=startSpeech()
    internal suspend fun highlightForTests(phrase:Phrase?)=highlightPhrase(phrase)
    internal fun readerSessionForTests() = session?.takeIf{selectionConfirmed}
    internal fun commentEditorVisibleForTests() = editor?.isShowing == true
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri==null)libraryEvent("CANCELLED") else if(!busy)importBook({store.stage(uri)},uri)
    }
    private val pickFolder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()){tree->
        if(tree!=null){val retained=sourceFolder.remember(tree);if(!retained)Toast.makeText(this,"Разрешение не сохранено: после перезапуска папку может потребоваться выбрать снова",Toast.LENGTH_LONG).show();browseFolder(tree)}
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        // A Readium Fragment needs its Publication before restoration. Rebuild from the durable
        // locator rather than asking FragmentManager to restore an unbound navigator.
        delegate.localNightMode=when(appearance.getString("theme","auto")){"dark"->AppCompatDelegate.MODE_NIGHT_YES;"light"->AppCompatDelegate.MODE_NIGHT_NO;else->AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM}
        super.onCreate(null)
        readingTrace = ReadingTrace(java.io.File(filesDir,"reading-last-session.json"))
        readingTrace.record(ReadingTrace.Event.CREATE,ReadingTrace.Source.LIFECYCLE,activityId)
        store = BookStore(this); engine = ReadiumEngine(this); bridge = LocatorBridge(this)
        ReadingMode.current(getSharedPreferences("reader",MODE_PRIVATE))
        if(prototypeControls)buildPrototypeUi()else buildReaderUi()
        player = PhrasePlayer(this, lifecycleScope, { highlightPhrase(it) }, { status.text = it }, { localSpeechAudio(it) })
        busy=true
        openingJob=lifecycleScope.launch {
            try {
                val previous = withContext(Dispatchers.IO) { store.current() }
                if (previous != null) load(previous) else if(prototypeControls)status.text="Откройте EPUB или пример. Это прототип P0."else {busy=false;showShelf()}
            } catch (e: Exception) { handle(e);if(!prototypeControls){busy=false;showShelf("Последняя книга недоступна. Внутренние копии и пометки не удалены.")} }finally{busy=false}
        }
    }
    private fun buildReaderUi() {
        val view=ReaderShell(this,READER_ID,::showReaderMenu,{showShelf()},::returnToPlace,::openSourceFolder,
            {if(!busy)openDocument.launch(arrayOf("application/epub+zip","application/octet-stream"))},
            {if(!busy)importBook({store.stage(assets.open("p0-sample.epub"))},sourceLabel="Пример P0.epub")},
            {if(book!=null){shell?.showReader();window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}},::selectShelfBook,::manageBook)
        shell=view;heading=view.heading;progress=view.progress;status=view.status
        notesButton=button("Пометки · 0"){showNotes()}
        view.setPanels(appearance.getBoolean("panels",true));val dark=Appearance.dark(appearance,resources.configuration);view.theme(dark)
        window.attributes=window.attributes.apply{screenBrightness=appearance.getFloat("brightness",-1f).let{if(it<0)-1f else Appearance.brightness(it)}}
        ViewCompat.setOnApplyWindowInsetsListener(view.root){root,insets->val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime());root.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
        view.slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seek:SeekBar){scrubbing=true;stopManualNavigation();rememberPlace()}
            override fun onProgressChanged(seek:SeekBar,value:Int,user:Boolean){if(user){progress.text="${value/100}%";scrubJob?.cancel();scrubJob=lifecycleScope.launch{delay(140);seekWholeBook(value)}}}
            override fun onStopTrackingTouch(seek:SeekBar){scrubJob?.cancel();scrubbing=false;seekWholeBook(seek.progress)}
        })
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){override fun handleOnBackPressed(){if(view.onShelf)finish()else showShelf()}})
        setContentView(view.root)
        androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
    }
    private fun showShelf(message:String="") {
        if(prototypeControls){showBooks();return}
        if(busy){status.text="Дождитесь открытия книги";return}
        shelfJob?.cancel();folderJob?.cancel();player.pause();scrubJob?.cancel();navigationJob?.cancel();resizeJob?.cancel()
        shelfJob=lifecycleScope.launch {
            try{navigator?.let{updateViewport(it)};val entries=withContext(Dispatchers.IO){store.entries()};shell?.shelf(entries,message);shell?.bookAvailable(book!=null);shell?.theme(Appearance.dark(appearance,resources.configuration));window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}catch(e:Exception){handle(e)}
        }
    }
    private fun selectShelfBook(entry:BookEntry){
        if(busy)return
        if(!entry.available){AlertDialog.Builder(this).setTitle("Копия недоступна").setMessage("Не удалось прочитать данные этой копии. Другие книги не затронуты. Можно выбрать другую запись или удалить недоступную копию через «Действия».").setPositiveButton("Закрыть",null).show();return}
        shelfJob?.cancel();folderJob?.cancel();busy=true;openingJob=lifecycleScope.launch{try{stopManualNavigation();captureLeaving();val selected=withContext(Dispatchers.IO){store.open(entry.id)};load(selected)}catch(e:Exception){handle(e);shell?.showShelf()}finally{busy=false}}
    }
    private fun manageBook(entry:BookEntry){
        if(busy)return
        AlertDialog.Builder(this).setTitle(entry.title).setItems(arrayOf("Сведения","Очистить пометки","Удалить книгу")){_,item->
            if(item==0)AlertDialog.Builder(this).setTitle("Сведения о книге").setMessage("${entry.title}\n${entry.sourceName ?: "Прежняя внутренняя копия"}\nИмпорт: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.importedAt))}\n${entry.progress?.let{"Прочитано: $it%"} ?: if(entry.savedPlace)"Позиция сохранена"else"Чтение не начато"}\nПометок: ${entry.notes}\nID: ${entry.id}").setPositiveButton("Закрыть",null).show()
            else AlertDialog.Builder(this).setTitle(if(item==1)"Очистить пометки?"else"Удалить внутреннюю книгу?").setMessage(if(item==1)"${entry.title}\nБудут удалены пометки и черновик. Книга, размер шрифта и место чтения сохранятся."else"${entry.title}\nБудут удалены внутренняя копия, пометки, черновик, позиция и её кеш. Исходный EPUB в папке останется.")
                .setPositiveButton(if(item==1)"Очистить"else"Удалить"){_,_->changeBookData(entry,item==2)}.setNegativeButton("Отмена",null).show()
        }.setNegativeButton("Закрыть",null).show()
    }
    private fun changeBookData(entry:BookEntry,delete:Boolean){
        if(busy)return;busy=true
        openingJob=lifecycleScope.launch {
            var outcomeMessage=""
            try {
                if(book?.id==entry.id){stopManualNavigation();captureLeaving()
                    if(!delete)session?.freeze(ReadingTrace.Source.NOTES)
                    if(delete){session?.freeze();readerJob?.cancelAndJoin();decorationJob?.cancelAndJoin();navigator?.let{supportFragmentManager.beginTransaction().remove(it).commitNow()};publication?.close();session=null;navigator=null;publication=null;book=null;notes=null;rendition=null;sectionNavigation=null;selectionConfirmed=false}
                }
                withContext(NonCancellable+Dispatchers.IO){if(delete)check(store.delete(entry.id)){"Копия удалена с полки; не все временные файлы удалось очистить"}else store.clearNotes(entry.id)}
                if(!delete && book?.id==entry.id){notes?.data?.put("notes",org.json.JSONArray());notes?.data?.remove("draft");session?.userNavigation();refreshNotes()}
            }catch(e:Exception){handle(e);outcomeMessage=status.text.toString()}finally{if(!delete && book?.id==entry.id)session?.userNavigation();busy=false;showShelf(outcomeMessage)}
        }
    }
    private fun openSourceFolder(){val tree=sourceFolder.saved();if(tree==null)pickFolder.launch(null)else AlertDialog.Builder(this).setTitle("Стартовая папка").setItems(arrayOf("Открыть папку","Выбрать другую папку")){_,i->if(i==0)browseFolder(tree)else pickFolder.launch(tree)}.setNegativeButton("Отмена",null).show()}
    private fun browseFolder(tree:android.net.Uri,documentId:String?=null) {
        if(busy)return
        folderJob?.cancel()
        folderJob=lifecycleScope.launch {
            try {
                val entries=withContext(Dispatchers.IO){sourceFolder.list(tree,documentId ?: android.provider.DocumentsContract.getTreeDocumentId(tree))}
                if(entries.isEmpty()){AlertDialog.Builder(this@MainActivity).setMessage("В папке нет EPUB или подпапок").setPositiveButton("Закрыть",null).show();return@launch}
                AlertDialog.Builder(this@MainActivity).setTitle("EPUB в папке").setItems(entries.map{(if(it.directory)"▸ "else"")+it.name}.toTypedArray()){_,index->val entry=entries[index];if(entry.directory)browseFolder(tree,android.provider.DocumentsContract.getDocumentId(entry.uri))else importBook({store.stage(entry.uri)},entry.uri,entry.name)}.setNegativeButton("Назад к полке"){_,_->showShelf()}.show()
            }catch(e:Exception){if(e is CancellationException)throw e;AlertDialog.Builder(this@MainActivity).setTitle("Папка недоступна").setMessage("Доступ к исходной папке утрачен или провайдер недоступен. Внутренние копии и пометки сохранены.").setPositiveButton("Выбрать папку"){_,_->pickFolder.launch(null)}.setNegativeButton("Книги"){_,_->showShelf()}.show()}
        }
    }
    private fun showReaderMenu(){
        if(busy){status.text="Дождитесь открытия книги";return}
        player.pause()
        AlertDialog.Builder(this).setTitle("Чтение").setItems(arrayOf("Книги","Оглавление","Пометки · ${notes?.notes?.size ?: 0}","Вернуться к предыдущему месту","Настройки чтения","Озвучка · 5 фраз","Диагностика")){_,which->when(which){0->showShelf();1->showContents();2->showNotes();3->returnToPlace();4->showReadingSettings();5->showSpeechControls();6->showTtsDiagnostics()}}.setNegativeButton("Закрыть",null).show()
    }
    private fun showSpeechControls(){
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
        val voices=RadioGroup(this).apply{orientation=RadioGroup.HORIZONTAL}
        Voice.entries.forEachIndexed{i,v->voices.addView(RadioButton(this).apply{id=100+i;text=v.label;isChecked=v==voice})}
        voices.setOnCheckedChangeListener{_,checked->player.reset();voice=if(checked==100)Voice.DMITRY else Voice.SVETLANA}
        box.addView(voices);box.addView(row(button("5 фраз"){requestSpeech()},button("Пауза"){player.pause()},button("Продолжить"){player.resume()}))
        AlertDialog.Builder(this).setTitle("Озвучка · проба из пяти фраз").setView(box).setPositiveButton("Закрыть",null).show()
    }
    private fun showReadingSettings(){
        player.pause()
        val oldBrightness=window.attributes.screenBrightness;val savedBrightness=appearance.getFloat("brightness",-1f)
        fun revertBrightness(){window.attributes=window.attributes.apply{screenBrightness=oldBrightness};appearance.edit { putFloat("brightness",savedBrightness) }}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),0,dp(20),0)}
        fun label(text:String){box.addView(TextView(this).apply{this.text=text})}
        label("Тема");val themes=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Авто — системная","Дневная","Ночная"));setSelection(listOf("auto","light","dark").indexOf(appearance.getString("theme","auto")).coerceAtLeast(0))};box.addView(themes)
        label("Шрифт с кириллицей");val fonts=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,Appearance.fontNames);setSelection(Appearance.fonts.indexOf(Appearance.font(appearance)))};box.addView(fonts)
        val sizeLabel=TextView(this);box.addView(sizeLabel);val size=SeekBar(this).apply{max=210;progress=(((notes?.data?.optDouble("fontSize",1.0) ?: 1.0)-.7)*100).toInt().coerceIn(0,210)};box.addView(size)
        fun sizeText(){sizeLabel.text="Размер: ${70+size.progress}%"};sizeText();size.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(s:SeekBar){};override fun onStopTrackingTouch(s:SeekBar){};override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){sizeText()}})
        label("Яркость только этого окна");val brightness=SeekBar(this).apply{max=95;progress=((effectiveBrightness()-.05f)*100).toInt().coerceIn(0,95)};box.addView(brightness)
        brightness.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(s:SeekBar){};override fun onStopTrackingTouch(s:SeekBar){appearance.edit { putFloat("brightness",effectiveBrightness()) }};override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){if(user)setBrightness((p+5)/100f)}})
        box.addView(button("Системная яркость"){window.attributes=window.attributes.apply{screenBrightness=-1f};appearance.edit { putFloat("brightness",-1f) }})
        AlertDialog.Builder(this).setTitle("Настройки чтения").setView(ScrollView(this).apply{addView(box)}).setPositiveButton("Применить"){_,_->
            appearance.edit { putString("theme",listOf("auto","light","dark")[themes.selectedItemPosition]);putString("font",Appearance.fonts[fonts.selectedItemPosition]) }
            reflow {notes?.data?.put("fontSize",(size.progress+70)/100.0);persist();navigator?.submitPreferences(preferences());shell?.theme(Appearance.dark(appearance,resources.configuration))
                delegate.localNightMode=when(appearance.getString("theme","auto")){"dark"->AppCompatDelegate.MODE_NIGHT_YES;"light"->AppCompatDelegate.MODE_NIGHT_NO;else->AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM}}
        }.setNegativeButton("Отмена"){_,_->revertBrightness()}.setOnCancelListener{revertBrightness()}.show()
    }
    private fun effectiveBrightness()=window.attributes.screenBrightness.takeIf{it>=0} ?: runCatching{android.provider.Settings.System.getInt(contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS,128)/255f}.getOrDefault(.5f)
    private fun setBrightness(value:Float){window.attributes=window.attributes.apply{screenBrightness=Appearance.brightness(value)}}
    private fun place():JSONObject? {
        val data=notes?.data ?: return null
        return if(data.optString("positionKind")=="visual")data.optJSONObject("visualPosition")?.let{JSONObject(it.toString()).put("kind","visual").put("bookId",book?.id)}else data.optJSONObject("positionV2")?.let{JSONObject(it.toString())}
    }
    private fun rememberPlace(){place()?.let{notes?.data?.put("previousPlace",it);persist();shell?.previousAvailable(true)}}
    private fun returnToPlace(){
        if(busy)return
        val currentNotes=notes ?: return
        val target=currentNotes.data.optJSONObject("previousPlace")?.let{JSONObject(it.toString())} ?: return
        val reader=session ?: return;val controller=sectionNavigation ?: return
        stopManualNavigation();navigationJob=lifecycleScope.launch{try{controller.run(SectionNavigation.Initiator.RETURN,target.getInt("chapter")){reader.restorePlace(target)};currentCoroutineContext().ensureActive();if(session!==reader)throw StaleReaderOperation();currentNotes.data.remove("previousPlace");currentNotes.save();shell?.previousAvailable(false)}catch(e:Exception){handle(e)}}
    }
    private fun reflow(change:()->Unit){
        val reader=session
        if(reader==null){change();return}
        player.pause();navigationJob?.cancel();resizeJob?.cancel()
        resizeJob=lifecycleScope.launch {
            try{reader.capture();val target=place();val operation=reader.freeze(ReadingTrace.Source.FONT);change();delay(100);reader.requireCurrent(operation)
                reader.awaitFont(notes?.data?.optDouble("fontSize",1.0) ?: 1.0,operation)
                if(target!=null)reader.restorePlace(target,operation)else reader.restoreInitial()
            }catch(e:Exception){handle(e)}
        }
    }
    private fun togglePanels(){val view=shell ?: return;val visible=!view.panels;reflow{view.setPanels(visible);appearance.edit { putBoolean("panels",visible) }}}
    private fun seekWholeBook(value:Int){
        val adapter=rendition ?: return;val reader=session ?: return;val controller=sectionNavigation ?: return
        if(busy || adapter.book.total<=0)return
        val (index,offset)=adapter.sourceAt((adapter.book.total.toDouble()*value/10000).toInt().coerceIn(0,adapter.book.total)) ?: return
        val start=offset.coerceAtMost(TextModel.length(adapter.book.chapters[index].text)-1)
        val target=JSONObject().put("chapter",index).put("start",start).put("end",start+1).put("quote",TextModel.slice(adapter.book.chapters[index].text,start,start+1))
        stopManualNavigation();navigationJob=lifecycleScope.launch{try{controller.run(SectionNavigation.Initiator.SLIDER,index){reader.navigateTo(target)}}catch(e:Exception){handle(e)}}
    }
    private fun handleReaderAction(action:JSONObject){
        when(action.optString("kind")){
            "brightness"->{val gesture=action.optString("gesture");if(brightnessGesture!=gesture){brightnessGesture=gesture;brightnessBase=effectiveBrightness()};setBrightness(brightnessBase+action.optDouble("delta").toFloat());if(action.optBoolean("end"))appearance.edit { putFloat("brightness",effectiveBrightness()) }}
            "panels"->togglePanels()
            "page"->{val nav=navigator ?: return;val reader=session ?: return;val controller=sectionNavigation ?: return
                stopManualNavigation();reader.userNavigation()
                navigationJob=lifecycleScope.launch{try{
                    val step=bridge.installedCall(nav,"JJ.pageStep(${action.getInt("direction")})") ?: return@launch
                    currentCoroutineContext().ensureActive();if(session!==reader || step.optString("documentId")!=reader.documentId)throw StaleReaderOperation()
                    reader.tracePageStep(step)
                    val delta=step.optInt("resource",0);val target=reader.chapterIndex()+delta
                    if(delta!=0 && target in reader.book.chapters.indices){val placement=if(delta>0)BoundaryPlacement.RESOURCE_START else BoundaryPlacement.END_PAGE;controller.boundary(target,placement,SectionNavigation.Initiator.TAP,step)}
                }catch(e:Exception){handle(e)}finally{withContext(NonCancellable){withTimeoutOrNull(500){bridge.installedCall(nav,"JJ.edgeComplete()")}}}}
            }
            "edge"->{val reader=session ?: return;val direction=action.optInt("direction");val target=reader.chapterIndex()+direction;if(target in checkNotNull(book).chapters.indices)navigateSection(target,if(direction<0)BoundaryPlacement.END_TOP else BoundaryPlacement.RESOURCE_START,SectionNavigation.Initiator.GESTURE)else navigator?.let{nav->lifecycleScope.launch{bridge.installedCall(nav,"JJ.edgeComplete()")}}}
        }
    }
    private fun buildPrototypeUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF7F3EB.toInt()) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        heading = TextView(this).apply { text = "JJReader · P0"; textSize = 19f; setPadding(dp(16), dp(10), dp(12), dp(4)) }
        root.addView(heading)
        root.addView(row(button("Открыть EPUB") { if (!busy) openDocument.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
            button("Пример P0") { if (!busy) importBook({store.stage(assets.open("p0-sample.epub"))},sourceLabel="Пример P0.epub") },button("Книги"){showBooks()}))
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
        .put("navigation",runCatching{BookStore.readAtomic(java.io.File(filesDir,"section-navigation.json"))}.getOrElse{JSONObject().put("schema",1)})
        .put("library",runCatching{BookStore.readAtomic(java.io.File(filesDir,"library-actions.json"))}.getOrElse{JSONObject().put("schema",1)})
        .put("tts",runCatching{JSONObject(player.diagnosticText())}.getOrElse{JSONObject().put("available",false)}).toString(2)
    private fun setUnified(enabled:Boolean) {
        ReadingMode.set(getSharedPreferences("reader",MODE_PRIVATE),if(enabled)"unified"else"sections")
    }
    private fun showDiagnosticActions() {
        AlertDialog.Builder(this).setTitle("Проверки P0").setItems(arrayOf("Сравнить маршруты: 4 сетевых запроса", "Начать новую трассу чтения", "Оглавление / разделы", "Режим: единая лента / прежний")) { _, which ->
            if(which==0) AlertDialog.Builder(this).setTitle("Сравнение голосов/маршрутов")
                .setMessage("4 запроса Microsoft: два голоса × два маршрута, последовательно, без кеша, prefetch и повторов. Только фраза «${VoiceProbe.TEXT}». Обычный маршрут не меняется. После проверки откройте диагностику и скопируйте общий результат.")
                .setPositiveButton("Запустить"){_,_->player.checkVoices(compareRoutes=true)}.setNegativeButton("Отмена",null).show()
            else if(which==2) showContents()
            else if(which==3) AlertDialog.Builder(this).setTitle("Режим чтения").setItems(arrayOf("Единая лента","Прежний режим по разделам")){_,mode->
                val previous=openingJob
                busy=true
                openingJob=lifecycleScope.launch {
                    try {
                        previous?.cancelAndJoin();busy=true
                        stopManualNavigation();captureLeaving()
                        setUnified(mode==0)
                        val current=withContext(Dispatchers.IO){store.current()}
                        if(current!=null)load(current)
                    }catch(e:Exception){handle(e)}finally{busy=false}
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
    private fun importBook(copy: () -> java.io.File,sourceUri:android.net.Uri?=null,sourceLabel:String?=null) {
        if(busy)return
        shelfJob?.cancel();folderJob?.cancel()
        stopManualNavigation();busy = true; status.text = "Импорт внутренней копии EPUB…"
        openingJob=lifecycleScope.launch {
            var staged: java.io.File? = null
            var created:Book?=null
            var cancelled=false
            try {
                val file=withContext(Dispatchers.IO){copy().also{staged=it}}
                val matches=withContext(Dispatchers.IO){store.identical(file)}
                val imported=if(matches.isEmpty()) {
                    libraryEvent("NEW_IMPORT")
                    withContext(Dispatchers.IO){val candidate=engine.open(file);try{store.commit(file,candidate.metadata.title ?: "Без названия",engine.chapters(candidate),sourceLabel ?: sourceUri?.let{uri->contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)else null}}).also{created=it}}finally{candidate.close()}}
                } else {
                    libraryEvent(if(matches.size==1)"REOPEN"else"CHOOSE_DUPLICATE")
                    val chosen=if(matches.size==1)matches.single()else chooseBook(matches)
                    if(chosen==null){libraryEvent("CANCELLED");return@launch}
                    withContext(Dispatchers.IO){store.open(chosen.id)}
                }
                captureLeaving()
                load(imported)
            } catch (e: Exception) { cancelled=e is CancellationException;if(cancelled)libraryEvent("CANCELLED");handle(e) }
            finally { staged?.delete();created?.takeIf{cancelled && book?.id!=it.id}?.let{withContext(NonCancellable+Dispatchers.IO){store.discardUnopenedImport(it)}};busy = false }
        }
    }
    private fun libraryEvent(outcome:String) {
        BookStore.writeAtomic(java.io.File(filesDir,"library-actions.json"),JSONObject().put("schema",1).put("outcome",outcome))
    }
    private suspend fun chooseBook(entries:List<BookEntry>):BookEntry?=suspendCancellableCoroutine{continuation->
        val labels=entries.map{(if(it.current)"● "else"")+it.title+" · пометок ${it.notes} · ${it.id.take(8)}"+(if(it.available)""else" · недоступна")}
        val dialog=AlertDialog.Builder(this).setTitle("Книги — внутренние копии").setItems(labels.toTypedArray()){_,which->
            if(continuation.isActive)continuation.resume(entries[which].takeIf{it.available})
        }.setNegativeButton("Отмена"){_,_->if(continuation.isActive)continuation.resume(null)}.setOnCancelListener{if(continuation.isActive)continuation.resume(null)}.create()
        continuation.invokeOnCancellation{dialog.dismiss()};dialog.show()
    }
    private fun showBooks() {
        if(!prototypeControls){showShelf();return}
        if(busy){status.text="Дождитесь открытия книги";return}
        player.pause();busy=true
        openingJob=lifecycleScope.launch {
            try {
                val entries=withContext(Dispatchers.IO){store.entries()}
                if(entries.isEmpty()){status.text="Внутренних копий пока нет";return@launch}
                val chosen=chooseBook(entries) ?: return@launch
                stopManualNavigation();captureLeaving()
                val selected=withContext(Dispatchers.IO){store.open(chosen.id)}
                load(selected)
            }catch(e:Exception){handle(e)}finally{busy=false}
        }
    }
    private suspend fun captureLeaving(){
        navigationJob?.cancelAndJoin();resizeJob?.cancelAndJoin()
        navigator?.let{nav->try{updateViewport(nav)}catch(e:Exception){if(e is CancellationException)throw e}}
    }
    private fun stopManualNavigation(){player.reset();speechChapter=null;navigationJob?.cancel();resizeJob?.cancel()}
    private suspend fun load(value: Book) {
        shelfJob?.cancel()
        openingJob=currentCoroutineContext()[Job]
        val mode=ReadingMode.current(getSharedPreferences("reader",MODE_PRIVATE))
        val unifiedEnabled=mode=="unified"
        val info=packageManager.getPackageInfo(packageName,0)
        val attempt=openingAttempts.begin(info.versionName ?: "unknown",info.longVersionCode,unifiedEnabled,rendition?.mode,mode)
        var pendingPublication:Publication?=null
        try {
        shell?.showReader()
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
        } catch(e:Exception) {throw e}
        currentCoroutineContext().ensureActive()
        check(openingAttempts.isCurrent(attempt)){"STALE_OPEN"}
        openingAttempts.stage(attempt,OpeningAttempt.Stage.NAVIGATOR_INIT)
        val (pub,derived)=prepared
        contents=preparingContents
        val adapter=RenditionAdapter(value,derived,mode=="sections")
        lastPrepareMs=derived?.prepareMs ?: 0;documentCacheHit=derived?.cacheHit ?: false
        session?.capture()
        session?.freeze()
        navigationJob?.cancel(); resizeJob?.cancel(); readerJob?.cancel();decorationJob?.cancel(); player.reset()
        navigator?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
        publication?.close()
        selectionConfirmed=false
        book = value; shell?.bookAvailable(true); notes = newNotes; publication = pub; pendingPublication=null; rendition=adapter; verifiedHref = null; mappingValid = false
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(pub).createFragmentFactory(
            initialLocator = adapter.initialLocator(newNotes.data),
            initialPreferences = preferences(),
            listener=object:EpubNavigatorFragment.Listener {
            override fun onExternalLinkActivated(url:org.readium.r2.shared.util.AbsoluteUrl){status.text="Внешние ссылки EPUB отключены"}
            override fun onJumpToLocator(locator:Locator) {
                val reader=session ?: return;val controller=sectionNavigation ?: return
                if(reader.book.id!=value.id || controller.busy || !reader.ready)return
                rememberPlace();stopManualNavigation();reader.userNavigation()
                navigationJob=lifecycleScope.launch {
                    try{val index=value.chapters.indexOfFirst{it.href.substringBefore('#')==locator.href.toString().substringBefore('#')}
                        if(index>=0)controller.run(SectionNavigation.Initiator.LINK,index){reader.confirmLink(index,locator)}
                    }catch(e:Exception){handle(e)}
                }
            }
        },
            configuration = EpubNavigatorFragment.Configuration {
                disablePageTurnsWhileScrolling=true
                shouldApplyInsetsPadding = false; selectionActionModeCallback = selectionCallback
                decorationTemplates = ReaderDecorations.templates()
            })
        val nav = supportFragmentManager.fragmentFactory.instantiate(classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        navigator = nav
        if(!prototypeControls)nav.addInputListener(object:org.readium.r2.navigator.input.InputListener{override fun onTap(event:org.readium.r2.navigator.input.TapEvent)=true})
        val reader = ReaderSession(value,newNotes,adapter.port(nav,bridge){index->nav.go(pub.readingOrder[index],animated=false)},readingTrace,activityId){player.reset();speechChapter=null}
        session = reader
        val controller=SectionNavigation(reader,java.io.File(filesDir,"section-navigation.json")){blocked->if(session===reader)bridge.installedCall(nav,if(blocked)"JJ.blockEdges()"else"JJ.edgeComplete()");Unit};sectionNavigation=controller
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
        shell?.showReader()
        player.bindBook(value.id)
        heading.text = value.title
        status.text = if(derived!=null)"Единая лента. Долгое нажатие → Пометка."else"Разделы: следующий жест на краю — переход. Долгое нажатие → Пометка."
        openMs=0
        readingTrace.metadata(buildMetadata())
        if(value.total==0){progress.text="0% · в книге нет читаемого текста";if(!adapter.sections){openingAttempts.success(attempt,adapter.mode);return}}
        readerJob = lifecycleScope.launch {
            var draftShown = false
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    try { if (reader.shouldRestoreInitial && editor == null) reader.restoreInitial() }
                    catch(e: Exception) { if(!reader.ready && e !is CancellationException)openingAttempts.failed(attempt,e);handle(e) } // Keep observation/capture siblings alive after an initial ACK failure.
                }
                launch {
                    nav.currentLocator.collect { locator ->
                        if (verifiedHref != locator.href.toString()) { mappingValid = false; verifiedHref = null }
                        // Engine start/progression events are observations, never durable anchors.
                        newNotes.data.put("engineLocator", locator.toJSON())
                        reader.traceState(ReadingTrace.Event.NATIVE,ReadingTrace.Source.NATIVE)
                        reader.observeResource()

                    }
                }
                if(adapter.sections)launch {
                    while(isActive) {
                        try {
                        if(adapter.sections && reader.ready && shell?.onShelf!=true && !controller.busy && !busy && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            if(!prototypeControls){
                                bridge.installedCall(nav,"JJ.configureInteractions(true)")
                                val action=bridge.installedCall(nav,"JJ.takeReaderAction()")
                                if(action!=null && action.optString("documentId")==reader.documentId)handleReaderAction(action)
                            }else {
                                val edge=bridge.installedCall(nav,"JJ.takeEdge()")
                                if(edge!=null && edge.optString("documentId")==reader.documentId){val target=reader.chapterIndex()+if(edge.optString("direction")=="next")1 else -1
                                    if(target in value.chapters.indices)navigateSection(target,if(edge.optString("direction")=="previous")BoundaryPlacement.END_TOP else BoundaryPlacement.RESOURCE_START,SectionNavigation.Initiator.GESTURE)else bridge.installedCall(nav,"JJ.edgeComplete()")}
                            }
                        }
                        }catch(e:Exception){if(e is CancellationException)throw e;handle(e)}
                        delay(40)
                    }
                }
                launch {
                    while (isActive) {
                        try { if(shell?.onShelf!=true)updateViewport(nav) } catch (e: Exception) { if (e is CancellationException && e !is StaleReaderOperation) throw e; progress.text = "Процент: DOM ещё не готов" }
                        if(!draftShown && reader.ready) {
                            draftShown=true
                            openMs=(System.nanoTime()-openBegan)/1_000_000;readingTrace.metadata(buildMetadata())
                            withContext(Dispatchers.IO){if(session===reader)store.select(value.id)}
                            if(session===reader){selectionConfirmed=true;openingAttempts.success(attempt,adapter.mode);readingTrace.metadata(buildMetadata())}
                            shell?.previousAvailable(newNotes.data.has("previousPlace"))
                            refreshNotes();newNotes.data.optJSONObject("draft")?.let{editNote(it)}
                        }
                        delay(350)
                    }
                }
            }
        }
        } catch(e:Exception) {
            pendingPublication?.close()
            openingAttempts.failed(attempt,e)
            if(e is CancellationException)throw e
            if(!openingAttempts.isCurrent(attempt))return
            if(!prototypeControls){val entries=withContext(Dispatchers.IO){store.entries()};shell?.shelf(entries,"Не удалось открыть книгу. Данные сохранены; можно выбрать другую копию.")}
            val failed=openingAttempts.json().getJSONObject("latest")
            status.text="Ошибка открытия: ${failed.getString("stage")} (${failed.getString("errorCode")}). Источник и пометки сохранены."
            AlertDialog.Builder(this).setTitle("Книга не открыта").setMessage(status.text)
                .setPositiveButton("Прежний режим"){_,_->setUnified(false);busy=true;openingJob=lifecycleScope.launch{try{load(value)}finally{busy=false}}}
                .setNegativeButton("Закрыть",null).show()
        }
    }
    private fun preferences() = EpubPreferences(scroll = true, fontSize = notes?.data?.optDouble("fontSize", 1.0) ?: 1.0, pageMargins = 2.0, publisherStyles = false,
        fontFamily=if(prototypeControls)null else FontFamily(Appearance.font(appearance)),theme=if(Appearance.dark(appearance,resources.configuration))Theme.DARK else Theme.LIGHT)
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
                if(data.optString("positionKind")=="visual")reader.restoreInitial() else if (anchor != null) reader.restore(anchor, operation = operation) else reader.restoreInitial()
            } catch (e: Exception) { handle(e) }
        }
    }
    private fun chapterIndex(nav: EpubNavigatorFragment) = session?.chapterIndex() ?: -1
    private fun changeResource(delta:Int){val reader=session ?: return;navigateSection(reader.chapterIndex()+delta,initiator=SectionNavigation.Initiator.BUTTON)}
    private fun navigateSection(index:Int,placement:BoundaryPlacement=BoundaryPlacement.RESOURCE_START,initiator:SectionNavigation.Initiator=SectionNavigation.Initiator.TOC,targetId:String?=null) {
        val adapter=rendition ?: return;val nav=navigator ?: return;val reader=session ?: return;val controller=sectionNavigation ?: return
        if(busy || index !in adapter.book.chapters.indices)return
        stopManualNavigation()
        navigationJob=lifecycleScope.launch {
            try {
                if(initiator==SectionNavigation.Initiator.TOC)rememberPlace()
                val resourceEntry=targetId?.substringAfter('#',"").isNullOrEmpty()
                controller.run(initiator,index,if(resourceEntry)placement else null){
                    if(adapter.unified!=null){
                        val (actual,offset)=adapter.boundary(index) ?: error("NO_TEXT")
                        val anchor=targetId?.let{bridge.call(nav,"JJ.forSection($actual).anchorForId(${JSONObject.quote(it)})")} ?: adapter.anchor(bridge,nav,actual,offset,offset+1)
                        anchor.put("chapter",actual);reader.navigateTo(anchor)
                    }else if(!resourceEntry && targetId!=null){
                        val link=checkNotNull(org.readium.r2.shared.publication.Link.fromJSON(JSONObject().put("href",targetId).put("type","application/xhtml+xml")))
                        val locator=checkNotNull(publication?.locatorFromLink(link));reader.confirmLink(index,locator)
                    }else reader.navigateBoundary(index,placement,ReadingTrace.Source.NATIVE)
                }
            }catch(e:Exception){handle(e)}
        }
    }
    private fun jumpSource(index:Int,targetId:String?=null)=navigateSection(index,targetId=targetId)
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
        val percent=TextModel.percent(checkNotNull(rendition).absolute(viewport.optInt("lastSourceChapter",index),end),current.total)
        progress.text="$percent% · раздел ${reader.chapterIndex()+1}/${current.chapters.size}"
        if(notes?.data?.optInt("progress",-1)!=percent){notes?.data?.put("progress",percent);persist()}
        if(!scrubbing)shell?.slider?.progress=if(current.total==0)0 else (checkNotNull(rendition).absolute(viewport.optInt("lastSourceChapter",index),end).toDouble()/current.total*10000).toInt().coerceIn(0,10000)
    }
    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            player.pause()
            val id = UUID.randomUUID().toString(); selectionSession = id
            lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { navigator?.let { bridge.call(it, "JJ.beginSelection(${JSONObject.quote(id)})") } }
            menu.add(0, 501, 0, "Пометка");menu.add(0,android.R.id.copy,1,"Копировать"); return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if(item.itemId==android.R.id.copy){val reader=session ?: return true;val id=selectionSession ?: return true;lifecycleScope.launch{try{val text=reader.selection(id).getString("quote");getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Цитата",text));mode.finish()}catch(e:Exception){handle(e)}};return true}
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
        val dialog = AlertDialog.Builder(this).setTitle(if(prototypeControls)"Пробная пометка · жёлтый"else"Комментарий").setView(content).setPositiveButton("Сохранить", null)
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
        val listDialog = AlertDialog.Builder(this).setTitle(if(prototypeControls)"Пробные пометки"else"Пометки").setItems(list.map { it.getString("quote").take(70) + " — " + it.getString("comment") }.toTypedArray()) { _, which ->
            val reader = session ?: return@setItems
            player.reset();speechChapter=null
            val note = JSONObject(list[which].toString())
            navigationJob?.cancel(); resizeJob?.cancel()
            navigationJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    rememberPlace()
                    sectionNavigation?.run(SectionNavigation.Initiator.NOTE,note.getInt("chapter")){reader.navigateTo(note)}
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
                val phrases=withContext(Dispatchers.Default){adapter.phrases(source,offset)}
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
        val controller=sectionNavigation ?: return
        if(rendition?.unified==null && chapter!=reader.chapterIndex()) {
            controller.boundary(chapter,false,SectionNavigation.Initiator.TTS)
            currentCoroutineContext().ensureActive()
        }
        val operation=reader.currentRevision
        val state=reader.context(chapter)
        speechNavigationSerial=state.optInt("navigationSerial")
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
        if(shell?.onShelf==true)shell?.message(status.text.toString())
    }
    override fun onConfigurationChanged(configuration:android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        if(editor!=null){shell?.theme(Appearance.dark(appearance,configuration));navigator?.submitPreferences(preferences());return}
        if(shell!=null)reflow{
            val dark=Appearance.dark(appearance,configuration);shell?.theme(dark);navigator?.submitPreferences(preferences())
            androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
        }
    }
    override fun onResume() { super.onResume(); if (navigator != null && shell?.onShelf!=true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    override fun onPause() {
        readingTrace.record(ReadingTrace.Event.PAUSE,ReadingTrace.Source.LIFECYCLE,activityId)
        session?.traceState(ReadingTrace.Event.PAUSE,ReadingTrace.Source.LIFECYCLE)
        player.pause()
        scrubJob?.cancel();folderJob?.cancel();shelfJob?.cancel()
        if(window.attributes.screenBrightness>=0)appearance.edit { putFloat("brightness",window.attributes.screenBrightness) }
        openingJob?.cancel()
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
