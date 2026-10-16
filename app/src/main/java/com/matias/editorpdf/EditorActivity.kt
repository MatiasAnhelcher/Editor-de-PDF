package com.matias.editorpdf

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.AnnotationStore
import com.matias.editorpdf.model.TextAnnotation
import com.matias.editorpdf.model.Tool
import com.matias.editorpdf.pdf.DocumentSession
import com.matias.editorpdf.pdf.PageRenderer
import com.matias.editorpdf.pdf.WriteDeniedException
import com.matias.editorpdf.view.PdfDocumentView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.max
import kotlin.math.roundToInt

class EditorActivity : AppCompatActivity(), PdfDocumentView.Listener {

    private lateinit var pdfView: PdfDocumentView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: View
    private lateinit var pageIndicator: TextView
    private lateinit var toolGroup: MaterialButtonToggleGroup
    private lateinit var optionsPanel: View
    private lateinit var swatches: LinearLayout
    private lateinit var widthSlider: Slider
    private lateinit var widthLabel: TextView
    private lateinit var straightCheck: CheckBox

    private lateinit var settings: Settings
    private lateinit var recents: Recents
    private val store = AnnotationStore()
    private var session: DocumentSession? = null
    private var renderer: PageRenderer? = null
    private var pageCount = 0
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

        settings = Settings(this)
        recents = Recents(this)
        pdfView = findViewById(R.id.pdf_view)
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        pageIndicator = findViewById(R.id.page_indicator)
        toolGroup = findViewById(R.id.tool_group)
        optionsPanel = findViewById(R.id.options_panel)
        swatches = findViewById(R.id.swatches)
        widthSlider = findViewById(R.id.width_slider)
        widthLabel = findViewById(R.id.width_label)
        straightCheck = findViewById(R.id.straight_check)

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
        }

        setupToolbar()
        setupTools()
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        pageIndicator.setOnClickListener { showGoToPage() }
        load()
    }

    override fun onPause() {
        super.onPause()
        val s = session ?: return
        if (renderer != null) recents.setPage(s.uri, pdfView.currentPage)
        if (store.isDirty) {
            val list = store.all()
            ioScope.launch { runCatching { s.saveDraft(list) } }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
        renderer = null
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
                pageCount = loaded.sizes.size
                docName = loaded.name
                toolbar.title = loaded.name
                recents.touch(s.uri, loaded.name)
                store.load(loaded.annotations.annotations, dirty = loaded.annotations.fromDraft)
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
                R.id.action_undo -> store.undo()
                R.id.action_redo -> store.redo()
                R.id.action_save -> save()
                R.id.action_save_copy -> if (renderer != null) saveCopyLauncher.launch(copyName())
                R.id.action_goto -> showGoToPage()
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
        toolButtons.entries.firstOrNull { it.value == initial }?.let { toolGroup.check(it.key) }
        toolGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) toolButtons[id]?.let { selectTool(it) }
        }
        straightCheck.isChecked = settings.straightHighlighter
        straightCheck.setOnCheckedChangeListener { _, checked -> settings.straightHighlighter = checked }
        widthSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                settings.setWidth(pdfView.tool, value)
                updateWidthLabel(pdfView.tool, value)
            }
        }
        selectTool(initial)
    }

    private fun selectTool(tool: Tool) {
        pdfView.tool = tool
        settings.tool = tool
        refreshOptions()
    }

    private fun refreshOptions() {
        val tool = pdfView.tool
        val hasOptions = tool != Tool.HAND && tool != Tool.ERASER
        optionsPanel.isVisible = hasOptions
        if (!hasOptions) return

        swatches.removeAllViews()
        val selected = settings.color(tool)
        val size = (30 * resources.displayMetrics.density).roundToInt()
        val gap = (6 * resources.displayMetrics.density).roundToInt()
        for (c in Settings.palette(tool)) {
            val v = View(this)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (c == selected) {
                    setStroke((3 * resources.displayMetrics.density).roundToInt(), Color.parseColor("#1E88E5"))
                } else {
                    setStroke((1 * resources.displayMetrics.density).roundToInt(), Color.parseColor("#55000000"))
                }
            }
            v.contentDescription = getString(R.string.color)
            v.setOnClickListener {
                settings.setColor(tool, c)
                refreshOptions()
            }
            swatches.addView(v, LinearLayout.LayoutParams(size, size).apply { marginEnd = gap })
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

    private fun updateWidthLabel(tool: Tool, value: Float) {
        val v = "%.1f".format(value)
        widthLabel.text = if (tool == Tool.TEXT) getString(R.string.text_size, v) else getString(R.string.stroke_width, v)
    }

    // --- PdfDocumentView.Listener ----------------------------------------------------------------

    override fun onPageChanged(page: Int, pageCount: Int) {
        pageIndicator.text = getString(R.string.page_indicator, page + 1, pageCount)
    }

    override fun onTextRequested(page: Int, x: Float, y: Float, existing: TextAnnotation?) {
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
                        val t = TextAnnotation(
                            Annotation.newId(), page, x, max(0f, y - size * 0.6f), text,
                            settings.color(Tool.TEXT), size,
                        )
                        store.commit(AnnotationStore.Change(emptyList(), listOf(t)))
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

    private fun wrap(v: View): View {
        val pad = (20 * resources.displayMetrics.density).roundToInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
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
