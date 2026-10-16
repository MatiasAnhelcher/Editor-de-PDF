package com.matias.editorpdf

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.AnnotationStore
import com.matias.editorpdf.model.InkAnnotation
import com.matias.editorpdf.model.InkKind
import com.matias.editorpdf.model.TextAnnotation
import com.matias.editorpdf.model.Tool
import com.matias.editorpdf.pdf.DocumentSession
import com.matias.editorpdf.pdf.ImageSnap
import com.matias.editorpdf.pdf.PageRenderer
import com.matias.editorpdf.pdf.TextSnap
import com.matias.editorpdf.view.AnnotationPainter
import com.matias.editorpdf.pdf.SearchHit
import com.matias.editorpdf.pdf.StudyText
import com.matias.editorpdf.pdf.WriteDeniedException
import com.matias.editorpdf.view.PdfDocumentView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.transition.AutoTransition
import android.transition.TransitionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class EditorActivity : AppCompatActivity(), PdfDocumentView.Listener {

    private lateinit var pdfView: PdfDocumentView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: View
    private lateinit var pageIndicator: TextView
    private lateinit var toolGroup: LinearLayout
    private lateinit var optionsPanel: View
    private lateinit var swatches: LinearLayout
    private lateinit var widthSlider: Slider
    private lateinit var widthLabel: TextView
    private lateinit var straightCheck: CompoundButton
    private lateinit var toolPanel: View
    private lateinit var colorMeaning: TextView
    private lateinit var snapCheck: CompoundButton
    private lateinit var lassoHint: TextView
    private lateinit var selectionDelete: View
    private lateinit var reviewBar: View
    private lateinit var studyPanel: StudyPanel
    private lateinit var bookmarks: Bookmarks
    private var studyText: StudyText? = null

    private lateinit var settings: Settings
    private lateinit var recents: Recents
    private val store = AnnotationStore()
    private var session: DocumentSession? = null
    private var renderer: PageRenderer? = null
    private var pageCount = 0
    private var pageSizes: List<android.util.SizeF> = emptyList()
    private var docName = ""
    private var saving = false
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val toolButtons by lazy {
        mapOf(
            R.id.tool_hand to Tool.HAND,
            R.id.tool_pen to Tool.PEN,
            R.id.tool_highlighter to Tool.HIGHLIGHTER,
            R.id.tool_underline to Tool.UNDERLINE,
            R.id.tool_text to Tool.TEXT,
            R.id.tool_eraser to Tool.ERASER,
            R.id.tool_lasso to Tool.LASSO,
            R.id.tool_strike to Tool.STRIKE,
            R.id.tool_shape to Tool.SHAPE,
            R.id.tool_note to Tool.NOTE,
        )
    }

    private val saveCopyLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) saveTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        applyInsets(findViewById(R.id.root))
        // Mientras se lee o se escribe, la pantalla no se apaga sola.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settings = Settings(this)
        recents = Recents(this)
        stats = StudyStats(this)
        pdfView = findViewById(R.id.pdf_view)
        pdfView.readingMode = settings.readingMode
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        pageIndicator = findViewById(R.id.page_indicator)
        toolGroup = findViewById(R.id.tool_group)
        optionsPanel = findViewById(R.id.options_panel)
        swatches = findViewById(R.id.swatches)
        widthSlider = findViewById(R.id.width_slider)
        widthLabel = findViewById(R.id.width_label)
        straightCheck = findViewById(R.id.straight_check)
        toolPanel = findViewById(R.id.tool_panel)
        colorMeaning = findViewById(R.id.color_meaning)
        snapCheck = findViewById(R.id.snap_check)
        lassoHint = findViewById(R.id.lasso_hint)
        selectionDelete = findViewById(R.id.selection_delete)
        selectionDelete.setOnClickListener { pdfView.deleteSelection() }
        findViewById<View>(R.id.review_cards).setOnClickListener { startCards() }
        reviewBar = findViewById(R.id.review_bar)
        bookmarks = Bookmarks(this)
        studyPanel = StudyPanel(this, findViewById(R.id.study_panel), studyHost)
        findViewById<View>(R.id.review_show_all).setOnClickListener { pdfView.revealAll(true) }
        findViewById<View>(R.id.review_hide_all).setOnClickListener { pdfView.revealAll(false) }
        findViewById<View>(R.id.review_exit).setOnClickListener { setReviewMode(false) }

        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        Uris.persist(this, uri)
        session = DocumentSession(this, uri)

        pdfView.settings = settings
        pdfView.store = store
        pdfView.listener = this
        store.onChanged = {
            pdfView.invalidate()
            updateMenu()
            if (studyPanel.isOpen) studyPanel.refresh()
        }

        setupToolbar()
        setupTools()
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        pageIndicator.setOnClickListener { showGoToPage() }
        load()
    }

    // --- Tiempo de estudio --------------------------------------------------------------------

    private lateinit var stats: StudyStats
    private var lastInteraction = 0L
    private var studyTicker: Job? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastInteraction = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        lastInteraction = System.currentTimeMillis()
        studyTicker?.cancel()
        // Cuenta el tiempo solo mientras se usa la app: si pasan 2 minutos sin tocar nada, se pausa.
        studyTicker = lifecycleScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (renderer == null || System.currentTimeMillis() - lastInteraction > IDLE_MS) continue
                val goal = stats.goalMinutes
                val before = stats.today().minutes
                stats.addSeconds((TICK_MS / 1000).toInt())
                if (before < goal && stats.today().minutes >= goal) celebrateGoal()
            }
        }
    }

    private fun celebrateGoal() {
        pdfView.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        )
        val streak = stats.streak()
        val msg = if (streak > 1) getString(R.string.goal_reached_streak, streak) else getString(R.string.goal_reached)
        Snackbar.make(pdfView, msg, Snackbar.LENGTH_LONG).show()
    }

    override fun onPause() {
        super.onPause()
        studyTicker?.cancel()
        studyTicker = null
        val s = session ?: return
        if (renderer != null) recents.setPage(s.uri, pdfView.currentPage)
        if (store.isDirty) {
            val list = store.all()
            ioScope.launch { runCatching { s.saveDraft(list) } }
        }
    }

    override fun onDestroy() {
        stopReading()
        tts?.shutdown()
        tts = null
        super.onDestroy()
        renderer?.close()
        renderer = null
        studyText?.let { t -> ioScope.launch { t.close() } }
        studyText = null
    }

    // ---------------------------------------------------------------------------------------

    private fun load() {
        val s = session ?: return
        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val name = s.displayName()
                    s.prepare()
                    val r = PageRenderer(s.workFile)
                    try {
                        Loaded(r, r.readPageSizes(), s.loadAnnotations(), name)
                    } catch (e: Throwable) {
                        r.close()
                        throw e
                    }
                }
                if (isDestroyed) {
                    loaded.renderer.close()
                    return@launch
                }
                renderer = loaded.renderer
                studyText = StudyText(s.workFile)
                pageCount = loaded.sizes.size
                pageSizes = loaded.sizes
                docName = loaded.name
                toolbar.title = loaded.name
                recents.touch(s.uri, loaded.name, loaded.sizes.size)
                loaded.sizes.firstOrNull()?.let { first ->
                    ioScope.launch { runCatching { Covers.ensure(applicationContext, s.uri.toString(), loaded.renderer, first.width, first.height) } }
                }
                // Las notas de texto de versiones anteriores no tenían cuadro y podían salirse de la hoja.
                val annotations = loaded.annotations.annotations.map { a ->
                    if (a is TextAnnotation && !a.sticky) fitToPage(a, loaded.sizes.getOrNull(a.page)?.width ?: 612f) else a
                }
                store.load(annotations, dirty = loaded.annotations.fromDraft)
                pdfView.setDocument(loaded.renderer, loaded.sizes, recents.lastPage(s.uri))
                if (loaded.annotations.fromDraft) {
                    Snackbar.make(pdfView, R.string.draft_restored, Snackbar.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = when (e) {
                    is SecurityException -> getString(R.string.error_protected_or_denied)
                    is FileNotFoundException -> getString(R.string.error_not_found)
                    else -> getString(R.string.error_open, e.message ?: e.javaClass.simpleName)
                }
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle(R.string.error_title)
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                    .show()
            } finally {
                progress.isVisible = false
            }
        }
    }

    private class Loaded(
        val renderer: PageRenderer,
        val sizes: List<android.util.SizeF>,
        val annotations: DocumentSession.Loaded,
        val name: String,
    )

    private fun save(after: (() -> Unit)? = null) {
        val s = session ?: return
        saveTo(s.uri, after)
    }

    private fun saveTo(target: Uri, after: (() -> Unit)? = null) {
        val s = session ?: return
        if (saving || renderer == null) return
        saving = true
        progress.isVisible = true
        val list = store.all()
        val version = store.version
        val pages = pageCount
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { s.save(list, target, pages) } }
            saving = false
            progress.isVisible = false
            result.onSuccess {
                if (target == s.uri) {
                    store.markSaved(version)
                    ioScope.launch { s.deleteDraft() }
                    Toast.makeText(this@EditorActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                    after?.invoke()
                } else {
                    // Se guardó una copia: seguimos trabajando sobre ella.
                    Uris.persist(this@EditorActivity, target)
                    store.markSaved(version)
                    ioScope.launch { s.deleteDraft() }
                    Toast.makeText(this@EditorActivity, R.string.copy_saved, Toast.LENGTH_SHORT).show()
                    startActivity(
                        Intent(this@EditorActivity, EditorActivity::class.java)
                            .setData(target)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    )
                    finish()
                }
            }.onFailure { e ->
                if (e is WriteDeniedException && target == s.uri) {
                    MaterialAlertDialogBuilder(this@EditorActivity)
                        .setTitle(R.string.read_only_title)
                        .setMessage(R.string.read_only_message)
                        .setPositiveButton(R.string.save_copy) { _, _ -> saveCopyLauncher.launch(copyName()) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    MaterialAlertDialogBuilder(this@EditorActivity)
                        .setTitle(R.string.error_title)
                        .setMessage(getString(R.string.error_save, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun copyName(): String {
        val base = docName.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "documento" }
        return "$base (anotado).pdf"
    }

    private fun handleBack() {
        if (studyPanel.isOpen) {
            studyPanel.close()
            return
        }
        if (pdfView.reviewMode) {
            setReviewMode(false)
            return
        }
        if (!store.isDirty || renderer == null) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_message)
            .setPositiveButton(R.string.save) { _, _ -> save { finish() } }
            .setNegativeButton(R.string.discard) { _, _ ->
                val s = session
                store.markSaved(store.version)
                if (s != null) ioScope.launch { s.deleteDraft() }
                finish()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    // --- Barra superior ----------------------------------------------------------------------

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { handleBack() }
        toolbar.inflateMenu(R.menu.editor)
        toolbar.menu.findItem(R.id.action_finger).isChecked = !settings.stylusOnly
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> if (renderer != null) studyPanel.open(StudyPanel.Tab.SEARCH)
                R.id.action_panel -> if (studyPanel.isOpen) studyPanel.close() else if (renderer != null) {
                    studyPanel.open(StudyPanel.Tab.OUTLINE)
                }
                R.id.action_bookmark -> toggleBookmark()
                R.id.action_review -> setReviewMode(!pdfView.reviewMode)
                R.id.action_undo -> store.undo()
                R.id.action_redo -> store.redo()
                R.id.action_save -> save()
                R.id.action_save_copy -> if (renderer != null) saveCopyLauncher.launch(copyName())
                R.id.action_goto -> showGoToPage()
                R.id.action_cards -> startCards()
                R.id.action_export -> exportSummary()
                R.id.action_reading_mode -> chooseReadingMode()
                R.id.action_read_aloud -> if (readingPage >= 0) stopReading() else startReading(pdfView.currentPage)
                R.id.action_share -> share()
                R.id.action_finger -> {
                    settings.stylusOnly = !settings.stylusOnly
                    item.isChecked = !settings.stylusOnly
                    Toast.makeText(
                        this,
                        if (settings.stylusOnly) R.string.finger_navigates else R.string.finger_draws,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            true
        }
        updateMenu()
    }

    private fun toggleBookmark() {
        val s = session ?: return
        if (renderer == null) return
        val page = pdfView.currentPage
        val added = bookmarks.toggle(s.uri, page)
        Toast.makeText(
            this,
            if (added) getString(R.string.bookmark_added, page + 1) else getString(R.string.bookmark_removed),
            Toast.LENGTH_SHORT,
        ).show()
        updateBookmarkIcon()
        if (studyPanel.isOpen) studyPanel.refresh()
    }

    private fun updateBookmarkIcon() {
        val s = session ?: return
        val marked = renderer != null && bookmarks.contains(s.uri, pdfView.currentPage)
        toolbar.menu?.findItem(R.id.action_bookmark)
            ?.setIcon(if (marked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
    }

    private fun setReviewMode(on: Boolean) {
        pdfView.reviewMode = on
        reviewBar.isVisible = on
        toolPanel.isVisible = !on
        toolbar.menu?.findItem(R.id.action_review)?.icon?.alpha = if (on) 255 else 170
    }

    private val studyHost = object : StudyPanel.Host {
        override val text: StudyText? get() = studyText
        override val pageCount: Int get() = this@EditorActivity.pageCount
        override fun annotations(): List<Annotation> = store.all()
        override fun bookmarks(): List<Int> = session?.let { bookmarks.list(it.uri) }.orEmpty()
        override fun removeBookmark(page: Int) {
            session?.let { bookmarks.remove(it.uri, page) }
            updateBookmarkIcon()
        }
        override fun goToPage(page: Int, y: Float?) {
            if (y != null) pdfView.scrollToPagePoint(page, y) else pdfView.scrollToPage(page)
            closePanelIfNarrow()
        }
        override fun showSearchMatch(hit: SearchHit) {
            val t = studyText ?: return
            lifecycleScope.launch {
                val rects = withContext(Dispatchers.IO) { runCatching { t.matchRects(hit) }.getOrDefault(emptyList()) }
                pdfView.showSearchMatch(hit.page, rects)
                closePanelIfNarrow()
            }
        }
        override fun clearSearchMatch() = pdfView.clearSearchMatch()
        override val currentPage: Int get() = pdfView.currentPage
        override fun pageAspect(page: Int): Float =
            pageSizes.getOrNull(page)?.let { it.height / it.width } ?: 1.4f
        override fun renderThumbnail(page: Int, widthPx: Int, callback: (Bitmap?) -> Unit) {
            val r = renderer ?: return callback(null)
            val sz = pageSizes.getOrNull(page) ?: return callback(null)
            r.render(page, RectF(0f, 0f, sz.width, sz.height), widthPx / sz.width, isNeeded = { true }, callback = callback)
        }
        override fun colorLabel(color: Int): String = settings.colorLabel(color)
        override fun exportSummary() = this@EditorActivity.exportSummary()
        override fun startCards() = this@EditorActivity.startCards()
    }

    // --- Resumen y tarjetas ------------------------------------------------------------------

    private val summaryLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) writeSummary(uri)
        }

    private fun studyItems(): List<Annotation> =
        store.all().filter { AnnotationPainter.isStudyItem(it) }
            .sortedWith(compareBy({ it.page }, { it.bounds.top }, { it.bounds.left }))

    private fun exportSummary() {
        if (renderer == null) return
        if (studyItems().isEmpty()) {
            Toast.makeText(this, R.string.summary_empty, Toast.LENGTH_LONG).show()
            return
        }
        val base = docName.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "documento" }
        summaryLauncher.launch("Resumen - $base.pdf")
    }

    private fun writeSummary(target: Uri) {
        val items = studyItems()
        val text = studyText
        progress.isVisible = true
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val rows = items.map { a ->
                        val body = when (a) {
                            is TextAnnotation -> a.text
                            else -> {
                                val area = (a as InkAnnotation).textArea()
                                text?.let { t -> runCatching { t.textIn(a.page, area) }.getOrNull() }.orEmpty()
                            }
                        }
                        val meaning = if (a is TextAnnotation) "" else settings.colorLabel(a.color)
                        val label = SummaryExporter.kindName(a) + if (meaning.isNotBlank()) " · $meaning" else ""
                        SummaryExporter.Item(a, body, label)
                    }
                    val out = contentResolver.openOutputStream(target, "wt") ?: throw FileNotFoundException()
                    out.use { SummaryExporter.write(it, docName.removeSuffix(".pdf"), rows) }
                }
            }
            progress.isVisible = false
            result.onSuccess { Toast.makeText(this@EditorActivity, R.string.summary_saved, Toast.LENGTH_SHORT).show() }
                .onFailure { e ->
                    MaterialAlertDialogBuilder(this@EditorActivity)
                        .setTitle(R.string.error_title)
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
        }
    }

    private fun startCards(all: Boolean = false) {
        val s = session ?: return
        val r = renderer ?: return
        val items = studyItems()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.cards_empty, Toast.LENGTH_LONG).show()
            return
        }
        val schedule = CardSchedule(this, s.uri)
        val due = if (all) items else items.filter { schedule.isDue(it.id) }
        if (due.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cards_none_title)
                .setMessage(R.string.cards_none_message)
                .setPositiveButton(R.string.cards_all) { _, _ -> startCards(all = true) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        FlashcardSession(
            activity = this,
            renderer = r,
            pageWidth = { pageSizes.getOrNull(it)?.width ?: 612f },
            pageHeight = { pageSizes.getOrNull(it)?.height ?: 792f },
            pageAnnotations = { store.forPage(it) },
            schedule = schedule,
            cards = due,
            onGoToPage = { a -> pdfView.scrollToPagePoint(a.page, a.bounds.top) },
        ).start()
    }

    /** En pantallas angostas (tablet vertical dividida, teléfono) el panel tapa la página: lo cerramos. */
    private fun closePanelIfNarrow() {
        if (resources.configuration.screenWidthDp < 800) {
            val root = findViewById<View>(R.id.study_panel)
            root.isVisible = false
        }
    }

    private fun updateMenu() {
        val menu = toolbar.menu ?: return
        listOf(R.id.action_undo to store.canUndo, R.id.action_redo to store.canRedo).forEach { (id, enabled) ->
            menu.findItem(id)?.let {
                it.isEnabled = enabled
                it.icon?.alpha = if (enabled) 255 else 80
            }
        }
        toolbar.subtitle = if (store.isDirty) getString(R.string.unsaved_changes) else null
    }

    private fun showGoToPage() {
        if (pageCount == 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.goto_hint, pageCount)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.goto_page)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().toIntOrNull()?.let { pdfView.scrollToPage(it - 1) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun share() {
        val s = session ?: return
        val doShare = {
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, s.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share)))
        }
        if (store.isDirty) save { doShare() } else doShare()
    }

    // --- Herramientas --------------------------------------------------------------------------

    private fun setupTools() {
        val initial = settings.tool
        for ((id, tool) in toolButtons) {
            findViewById<View>(id).setOnClickListener { button ->
                button.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                (toolPanel.parent as? ViewGroup)?.let {
                    TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(180))
                }
                button.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction {
                    button.animate().scaleX(1f).scaleY(1f).setDuration(160)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                }.start()
                if (pdfView.tool == tool) {
                    // Tocar otra vez la herramienta elegida muestra u oculta sus opciones.
                    optionsHidden = !optionsHidden
                    refreshOptions()
                } else {
                    optionsHidden = false
                    selectTool(tool)
                }
            }
        }
        straightCheck.isChecked = settings.straightHighlighter
        straightCheck.setOnCheckedChangeListener { _, checked -> settings.straightHighlighter = checked }
        snapCheck.isChecked = settings.snapToText
        snapCheck.setOnCheckedChangeListener { _, checked -> settings.snapToText = checked }
        widthSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.setWidth(pdfView.tool, value)
                updateWidthLabel(pdfView.tool, value)
            }
        }
        selectTool(initial)
    }

    private var optionsHidden = false

    private fun selectTool(tool: Tool) {
        pdfView.tool = tool
        settings.tool = tool
        refreshOptions()
    }

    /** El dock marca la herramienta elegida y pinta lápiz, resaltador y subrayado con su color actual. */
    private fun refreshToolButtons() {
        val selectedBg = MaterialColors.getColor(toolGroup, com.google.android.material.R.attr.colorSecondaryContainer)
        val normalTint = MaterialColors.getColor(toolGroup, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val selectedTint = MaterialColors.getColor(toolGroup, com.google.android.material.R.attr.colorOnSecondaryContainer)
        for ((id, tool) in toolButtons) {
            val b = findViewById<MaterialButton>(id)
            val on = pdfView.tool == tool
            b.backgroundTintList = ColorStateList.valueOf(if (on) selectedBg else Color.TRANSPARENT)
            val inkColor = when (tool) {
                Tool.PEN, Tool.HIGHLIGHTER, Tool.UNDERLINE, Tool.STRIKE, Tool.SHAPE, Tool.NOTE -> darkenForIcon(settings.color(tool))
                else -> null
            }
            b.iconTint = ColorStateList.valueOf(inkColor ?: if (on) selectedTint else normalTint)
        }
    }

    /** Los colores claros del resaltador se oscurecen un poco para que el ícono se lea bien. */
    private fun darkenForIcon(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color or 0xFF000000.toInt(), hsv)
        if (hsv[1] > 0.15f) hsv[2] = hsv[2].coerceAtMost(0.82f)
        return Color.HSVToColor(hsv)
    }

    private fun refreshOptions() {
        val tool = pdfView.tool
        refreshToolButtons()
        val hasOptions = tool != Tool.HAND && tool != Tool.ERASER && !(optionsHidden && tool != Tool.LASSO)
        optionsPanel.isVisible = hasOptions
        if (!hasOptions) return

        swatches.removeAllViews()
        val lasso = tool == Tool.LASSO
        val selected = if (lasso) -1 else settings.color(tool)
        val size = (34 * resources.displayMetrics.density).roundToInt()
        val gap = (4 * resources.displayMetrics.density).roundToInt()
        for (c in Settings.palette(tool)) {
            val v = View(this)
            val d = resources.displayMetrics.density
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c or 0xFF000000.toInt())
                setStroke((1 * d).roundToInt(), 0x22000000)
            }
            v.background = if (c == selected) {
                val ring = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke((2 * d).roundToInt(), MaterialColors.getColor(v, com.google.android.material.R.attr.colorPrimary, Color.BLACK))
                }
                LayerDrawable(arrayOf(ring, dot)).apply {
                    val inset = (5 * d).roundToInt()
                    setLayerInset(1, inset, inset, inset, inset)
                }
            } else {
                InsetDrawable(dot, (2 * d).roundToInt())
            }
            v.contentDescription = getString(R.string.color)
            v.setOnClickListener {
                if (lasso) {
                    pdfView.recolorSelection(c)
                } else {
                    settings.setColor(tool, c)
                    refreshOptions()
                }
            }
            if (tool == Tool.HIGHLIGHTER || tool == Tool.UNDERLINE) {
                v.setOnLongClickListener {
                    renameColor(c)
                    true
                }
            }
            swatches.addView(v, LinearLayout.LayoutParams(size, size).apply { marginEnd = gap })
        }

        colorMeaning.isVisible = tool == Tool.HIGHLIGHTER || tool == Tool.UNDERLINE
        colorMeaning.text = settings.colorLabel(selected).ifBlank { getString(R.string.rename_color) }
        snapCheck.isVisible = tool == Tool.HIGHLIGHTER || tool == Tool.UNDERLINE || tool == Tool.STRIKE
        refreshShapeTypes(tool)
        lassoHint.isVisible = lasso
        selectionDelete.isVisible = lasso && pdfView.selectionCount > 0
        lassoHint.text = if (lasso && pdfView.selectionCount > 0) {
            getString(R.string.lasso_selected, pdfView.selectionCount)
        } else {
            getString(R.string.lasso_hint)
        }
        val noWidth = lasso || tool == Tool.NOTE
        widthLabel.isVisible = !noWidth
        widthSlider.isVisible = !noWidth
        findViewById<View>(R.id.options_divider).isVisible = !noWidth
        if (noWidth) {
            straightCheck.isVisible = false
            return
        }

        val range = Settings.range(tool)
        val value = settings.width(tool)
        // Orden seguro para no dejar el valor fuera de rango en ningún momento.
        widthSlider.stepSize = 0f
        widthSlider.valueFrom = 0f
        widthSlider.valueTo = 100f
        widthSlider.value = value
        widthSlider.valueFrom = range.min
        widthSlider.valueTo = range.max
        updateWidthLabel(tool, value)
        straightCheck.isVisible = tool == Tool.HIGHLIGHTER
        widthSlider.thumbTintList = ColorStateList.valueOf(settings.color(tool) or 0xFF000000.toInt())
    }

    /** Botones para elegir la forma (rectángulo, círculo, línea o flecha) de la herramienta Formas. */
    private fun refreshShapeTypes(tool: Tool) {
        val row = findViewById<LinearLayout>(R.id.shape_types)
        row.isVisible = tool == Tool.SHAPE
        if (tool != Tool.SHAPE) return
        row.removeAllViews()
        val d = resources.displayMetrics.density
        val selectedBg = MaterialColors.getColor(row, com.google.android.material.R.attr.colorSecondaryContainer)
        val tint = MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val shapes = listOf(
            com.matias.editorpdf.model.ShapeType.RECT to (R.drawable.ic_shape_rect to R.string.shape_rect),
            com.matias.editorpdf.model.ShapeType.ELLIPSE to (R.drawable.ic_shape_ellipse to R.string.shape_ellipse),
            com.matias.editorpdf.model.ShapeType.LINE to (R.drawable.ic_shape_line to R.string.shape_line),
            com.matias.editorpdf.model.ShapeType.ARROW to (R.drawable.ic_shape_arrow to R.string.shape_arrow),
        )
        for ((type, res) in shapes) {
            val b = android.widget.ImageButton(this)
            b.setImageResource(res.first)
            b.contentDescription = getString(res.second)
            b.tooltipText = getString(res.second)
            b.imageTintList = ColorStateList.valueOf(tint)
            b.background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(if (settings.shapeType == type) selectedBg else Color.TRANSPARENT)
            }
            b.setOnClickListener {
                settings.shapeType = type
                refreshShapeTypes(tool)
            }
            val size = (38 * d).roundToInt()
            row.addView(b, LinearLayout.LayoutParams(size, size).apply { marginEnd = (2 * d).roundToInt() })
        }
    }

    private fun renameColor(color: Int) {
        val input = EditText(this).apply {
            setText(settings.colorLabel(color))
            setSelection(text.length)
            hint = getString(R.string.rename_color_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_color)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.setColorLabel(color, input.text.toString())
                refreshOptions()
                if (studyPanel.isOpen) studyPanel.refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateWidthLabel(tool: Tool, value: Float) {
        val v = "%.1f".format(value)
        widthLabel.text = if (tool == Tool.TEXT) getString(R.string.text_size, v) else getString(R.string.stroke_width, v)
    }

    // --- Modo de lectura y lectura en voz alta ---------------------------------------------------

    private fun chooseReadingMode() {
        val modes = ReadingMode.entries
        val labels = arrayOf(
            getString(R.string.reading_normal),
            getString(R.string.reading_sepia),
            getString(R.string.reading_night),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reading_mode)
            .setSingleChoiceItems(labels, modes.indexOf(settings.readingMode)) { d, which ->
                settings.readingMode = modes[which]
                pdfView.readingMode = modes[which]
                d.dismiss()
            }
            .show()
    }

    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false

    /** Página que se está leyendo en voz alta; -1 si no se lee nada. */
    private var readingPage = -1
    private var readingBar: Snackbar? = null

    private fun startReading(page: Int) {
        if (studyText == null) return
        readingPage = page
        readingBar?.dismiss()
        readingBar = Snackbar.make(pdfView, R.string.reading_aloud, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.stop) { stopReading() }
            .also { it.show() }
        if (tts == null) {
            tts = android.speech.tts.TextToSpeech(this) { status -> runOnUiThread { onTtsReady(status) } }
        } else if (ttsReady) {
            speakPage(page)
        }
    }

    private fun onTtsReady(status: Int) {
        val engine = tts ?: return
        if (status != android.speech.tts.TextToSpeech.SUCCESS) {
            stopReading()
            Toast.makeText(this, R.string.read_aloud_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        ttsReady = true
        // Español rioplatense si el motor lo tiene; si no, cualquier español.
        val r = engine.setLanguage(java.util.Locale("es", "AR"))
        if (r == android.speech.tts.TextToSpeech.LANG_MISSING_DATA || r == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(java.util.Locale("es"))
        }
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                runOnUiThread { onPageRead(utteranceId) }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { stopReading() }
            }
        })
        if (readingPage >= 0) speakPage(readingPage)
    }

    private fun speakPage(page: Int) {
        val text = studyText ?: return stopReading()
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) { runCatching { text.plainText(page) }.getOrDefault("") }
            if (readingPage != page) return@launch
            if (content.isBlank()) {
                stopReading()
                Toast.makeText(this@EditorActivity, R.string.read_aloud_no_text, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (pdfView.currentPage != page) pdfView.scrollToPage(page)
            val chunks = splitForSpeech(content, android.speech.tts.TextToSpeech.getMaxSpeechInputLength() - 200)
            chunks.forEachIndexed { i, c ->
                val mode = if (i == 0) android.speech.tts.TextToSpeech.QUEUE_FLUSH else android.speech.tts.TextToSpeech.QUEUE_ADD
                // Solo el último trozo lleva el id de la página: al terminarlo se pasa a la siguiente.
                val id = if (i == chunks.lastIndex) "page:$page" else "part:$page:$i"
                tts?.speak(c, mode, null, id)
            }
        }
    }

    private fun onPageRead(id: String?) {
        val page = id?.removePrefix("page:")?.takeIf { id.startsWith("page:") }?.toIntOrNull() ?: return
        if (page != readingPage) return
        if (page + 1 < pageCount) {
            readingPage = page + 1
            speakPage(page + 1)
        } else {
            stopReading()
        }
    }

    private fun stopReading() {
        readingPage = -1
        tts?.stop()
        readingBar?.dismiss()
        readingBar = null
    }

    /** Corta el texto en trozos que el motor de voz acepta, sin partir oraciones si se puede. */
    private fun splitForSpeech(text: String, max: Int): List<String> {
        val clean = text.replace(Regex("-\\n(?=\\p{Ll})"), "").replace(Regex("\\s+"), " ").trim()
        val out = ArrayList<String>()
        var rest = clean
        while (rest.length > max) {
            var cut = rest.lastIndexOf(". ", max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out.add(rest.trim())
        return out
    }

    // --- PdfDocumentView.Listener ----------------------------------------------------------------

    override fun onPageChanged(page: Int, pageCount: Int) {
        pageIndicator.text = getString(R.string.page_indicator, page + 1, pageCount)
        updateBookmarkIcon()
        lastInteraction = System.currentTimeMillis()
        if (renderer != null) session?.let { stats.markPage(it.uri.toString(), page) }
    }

    override fun onSelectionChanged(count: Int) {
        if (pdfView.tool == Tool.LASSO) refreshOptions()
    }

    override fun onStraightMarkAdded(a: InkAnnotation) {
        if (!settings.snapToText) return
        val r = renderer ?: return
        val t = studyText
        val sz = pageSizes.getOrNull(a.page) ?: return
        lifecycleScope.launch {
            val snapped = withContext(Dispatchers.IO) {
                (t?.let { runCatching { TextSnap.snap(it, a) }.getOrNull() })
                    ?: runCatching { ImageSnap.snap(r, a, sz.width, sz.height) }.getOrNull()
            }
            if (!snapped.isNullOrEmpty()) store.amendLast(a, snapped)
        }
    }

    override fun onNoteRequested(page: Int, x: Float, y: Float, existing: TextAnnotation?) {
        val input = EditText(this).apply {
            setText(existing?.text.orEmpty())
            setSelection(text.length)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.note_hint)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_note else R.string.sticky_note)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trimEnd()
                when {
                    existing == null -> if (text.isNotBlank()) {
                        val note = TextAnnotation(
                            Annotation.newId(), page, x, y, text, settings.color(Tool.NOTE), 12f, sticky = true,
                        )
                        store.commit(AnnotationStore.Change(emptyList(), listOf(note)))
                    }
                    text.isBlank() -> store.commit(AnnotationStore.Change(listOf(existing), emptyList()))
                    text != existing.text -> store.commit(AnnotationStore.Change(listOf(existing), listOf(existing.withText(text))))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ ->
                store.commit(AnnotationStore.Change(listOf(existing), emptyList()))
            }
        }
        val dialog = builder.create()
        // Una nota nueva abre el teclado; una existente se muestra para leerla.
        if (existing == null) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        if (existing == null) input.requestFocus()
    }

    override fun onTextRequested(page: Int, x: Float, y: Float, existing: TextAnnotation?, boxWidth: Float) {
        val input = EditText(this).apply {
            setText(existing?.text.orEmpty())
            setSelection(text.length)
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = getString(R.string.text_hint)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_text else R.string.edit_text)
            .setView(wrap(input))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trimEnd()
                if (existing == null) {
                    if (text.isNotBlank()) {
                        val size = settings.width(Tool.TEXT)
                        val t = if (boxWidth > 0f) {
                            TextAnnotation(Annotation.newId(), page, x, y, text, settings.color(Tool.TEXT), size, boxWidth)
                        } else {
                            fitTextBox(TextAnnotation(
                                Annotation.newId(), page, x, max(0f, y - size * 0.6f), text,
                                settings.color(Tool.TEXT), size,
                            ))
                        }
                        store.commit(AnnotationStore.Change(emptyList(), listOf(t)))
                        // El cuadro nuevo queda elegido para poder acomodarlo enseguida.
                        pdfView.selectText(t.id)
                    }
                } else if (text.isBlank()) {
                    store.commit(AnnotationStore.Change(listOf(existing), emptyList()))
                } else if (text != existing.text) {
                    store.commit(AnnotationStore.Change(listOf(existing), listOf(existing.withText(text))))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ ->
                store.commit(AnnotationStore.Change(listOf(existing), emptyList()))
            }
        }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /**
     * Cuadro para un texto tocado (sin dibujar): tan ancho como el texto, pero sin pasarse del margen
     * derecho de la página; si no entra, el texto baja de renglón dentro del cuadro.
     */
    private fun fitTextBox(t: TextAnnotation): TextAnnotation =
        fitTextBox(t, pageSizes.getOrNull(t.page)?.width ?: 612f)

    /** Deja el cuadro entero dentro de la hoja; si no tenía cuadro, le arma uno. */
    private fun fitToPage(t: TextAnnotation, pageWidth: Float): TextAnnotation {
        if (t.boxWidth <= 0f) return fitTextBox(t, pageWidth)
        if (t.x >= 0f && t.x + t.boxWidth <= pageWidth) return t
        val margin = 18f
        val width = t.boxWidth.coerceAtMost(pageWidth - 2 * margin)
        val x = t.x.coerceIn(0f, max(0f, pageWidth - margin - width))
        return TextAnnotation(t.id, t.page, x, t.y, t.text, t.color, t.size, width)
    }

    private fun fitTextBox(t: TextAnnotation, pageWidth: Float): TextAnnotation {
        val margin = 18f
        val natural = TextAnnotation(t.id, t.page, t.x, t.y, t.text, t.color, t.size).bounds.width() + 2f
        val minWidth = t.size * 8f
        // Si se tocó muy cerca del borde, el cuadro se corre a la izquierda para tener lugar.
        val x = t.x.coerceAtMost(max(0f, pageWidth - margin - min(natural, minWidth)))
        val width = natural.coerceAtMost(pageWidth - margin - x).coerceAtLeast(min(natural, t.size * 2f))
        return TextAnnotation(t.id, t.page, x, t.y, t.text, t.color, t.size, width)
    }

    private fun wrap(v: View): View {
        val pad = (20 * resources.displayMetrics.density).roundToInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    companion object {
        private const val TICK_MS = 30_000L
        private const val IDLE_MS = 120_000L
    }
}

/** Aplica los márgenes de las barras del sistema (pantalla completa en Android 15+). */
fun applyInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
}

object Uris {
    /** Conserva el permiso para volver a abrir (y guardar) el archivo más adelante. */
    fun persist(context: android.content.Context, uri: Uri) {
        val cr = context.contentResolver
        try {
            cr.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
    }
}
