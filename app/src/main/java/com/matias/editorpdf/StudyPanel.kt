package com.matias.editorpdf

import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.widget.ImageView
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.InkAnnotation
import com.matias.editorpdf.model.InkKind
import com.matias.editorpdf.model.TextAnnotation
import com.matias.editorpdf.pdf.OutlineEntry
import com.matias.editorpdf.pdf.SearchHit
import com.matias.editorpdf.pdf.StudyText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Panel lateral de estudio: índice del libro, páginas marcadas, lista de resaltados y notas, y búsqueda.
 */
class StudyPanel(
    private val activity: AppCompatActivity,
    private val root: View,
    private val host: Host,
) {
    interface Host {
        val text: StudyText?
        val pageCount: Int
        fun annotations(): List<Annotation>
        fun bookmarks(): List<Int>
        fun removeBookmark(page: Int)
        fun goToPage(page: Int, y: Float? = null)
        fun showSearchMatch(hit: SearchHit)
        fun clearSearchMatch()
        val currentPage: Int
        fun pageAspect(page: Int): Float
        fun renderThumbnail(page: Int, widthPx: Int, callback: (Bitmap?) -> Unit)
        fun colorLabel(color: Int): String
        fun exportSummary()
        fun startCards()
    }

    enum class Tab { PAGES, OUTLINE, BOOKMARKS, HIGHLIGHTS, SEARCH }

    private class Row(
        val title: String,
        val subtitle: String,
        val color: Int?,
        val indent: Int,
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)? = null,
    )

    private val tabs: TabLayout = root.findViewById(R.id.study_tabs)
    private val searchRow: View = root.findViewById(R.id.search_row)
    private val searchInput: EditText = root.findViewById(R.id.search_input)
    private val searchProgress: View = root.findViewById(R.id.search_progress)
    private val status: TextView = root.findViewById(R.id.study_status)
    private val list: RecyclerView = root.findViewById(R.id.study_list)
    private val adapter = RowAdapter()
    private val thumbAdapter = ThumbAdapter()
    private val highlightTools: View = root.findViewById(R.id.highlight_tools)
    private val filters: ChipGroup = root.findViewById(R.id.highlight_filters)
    private var colorFilter: Int? = null
    private val thumbs = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }
    private val pendingThumbs = HashSet<Int>()

    private var outline: List<OutlineEntry>? = null
    private var searchHits: List<SearchHit> = emptyList()
    private var lastQuery = ""
    private var job: Job? = null
    private val highlightTexts = HashMap<String, String>()

    val isOpen: Boolean get() = root.isVisible
    private val currentTab: Tab get() = Tab.entries[tabs.selectedTabPosition.coerceAtLeast(0)]

    init {
        listOf(R.string.tab_pages, R.string.tab_outline, R.string.tab_bookmarks, R.string.tab_highlights, R.string.tab_search).forEach {
            tabs.addTab(tabs.newTab().setText(it))
        }
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = refresh()
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = refresh()
        })
        root.findViewById<View>(R.id.study_close).setOnClickListener { close() }
        root.findViewById<View>(R.id.export_summary).setOnClickListener { host.exportSummary() }
        root.findViewById<View>(R.id.start_cards).setOnClickListener { host.startCards() }
        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                runSearch(searchInput.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }
    }

    fun open(tab: Tab) {
        root.isVisible = true
        tabs.getTabAt(tab.ordinal)?.select()
        refresh()
        if (tab == Tab.SEARCH) {
            searchInput.requestFocus()
            searchInput.post {
                activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(searchInput, 0)
            }
        }
    }

    fun close() {
        job?.cancel()
        hideKeyboard()
        root.isVisible = false
        host.clearSearchMatch()
    }

    /** Vuelve a cargar la pestaña visible (por ejemplo, después de agregar un resaltado o un marcador). */
    fun refresh() {
        if (!isOpen) return
        searchRow.isVisible = currentTab == Tab.SEARCH
        highlightTools.isVisible = currentTab == Tab.HIGHLIGHTS
        if (currentTab == Tab.PAGES) {
            if (list.adapter !== thumbAdapter) {
                list.layoutManager = GridLayoutManager(activity, 3)
                list.adapter = thumbAdapter
            }
        } else if (list.adapter !== adapter) {
            list.layoutManager = LinearLayoutManager(activity)
            list.adapter = adapter
        }
        if (currentTab != Tab.SEARCH) {
            job?.cancel()
            searchProgress.isVisible = false
        }
        when (currentTab) {
            Tab.PAGES -> showPages()
            Tab.OUTLINE -> showOutline()
            Tab.BOOKMARKS -> showBookmarks()
            Tab.HIGHLIGHTS -> showHighlights()
            Tab.SEARCH -> showSearchResults()
        }
    }

    // --- Miniaturas --------------------------------------------------------------------------

    private fun showPages() {
        thumbAdapter.notifyDataSetChanged()
        status.isVisible = false
        list.post { list.scrollToPosition(host.currentPage) }
    }

    private inner class ThumbAdapter : RecyclerView.Adapter<ThumbAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.thumb)
            val label: TextView = v.findViewById(R.id.label)
            var page = -1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_thumb, parent, false))

        override fun getItemCount() = host.pageCount

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.page = position
            holder.label.text = (position + 1).toString()
            holder.label.isActivated = position == host.currentPage
            holder.label.setTypeface(null, if (position == host.currentPage) Typeface.BOLD else Typeface.NORMAL)
            val widthPx = (110 * activity.resources.displayMetrics.density).toInt()
            holder.image.minimumHeight = (widthPx * host.pageAspect(position)).toInt()
            val cached = thumbs.get(position)
            holder.image.setImageBitmap(cached)
            if (cached == null && pendingThumbs.add(position)) {
                host.renderThumbnail(position, widthPx) { bmp ->
                    pendingThumbs.remove(position)
                    if (bmp != null) {
                        thumbs.put(position, bmp)
                        if (holder.page == position) holder.image.setImageBitmap(bmp)
                    }
                }
            }
            holder.itemView.setOnClickListener { host.goToPage(position) }
        }
    }

    // --- Índice ------------------------------------------------------------------------------

    private fun showOutline() {
        val cached = outline
        if (cached != null) {
            setRows(
                cached.map { e ->
                    Row(e.title, if (e.page >= 0) page(e.page) else "", null, e.level, onClick = {
                        if (e.page >= 0) host.goToPage(e.page)
                    })
                },
                if (cached.isEmpty()) activity.getString(R.string.no_outline) else null,
            )
            return
        }
        val text = host.text ?: return setRows(emptyList(), activity.getString(R.string.loading))
        setRows(emptyList(), activity.getString(R.string.loading))
        job?.cancel()
        job = activity.lifecycleScope.launch {
            outline = withContext(Dispatchers.IO) { runCatching { text.outline() }.getOrDefault(emptyList()) }
            if (currentTab == Tab.OUTLINE) showOutline()
        }
    }

    /** Nombre del capítulo en el que está [page], según el índice del libro. */
    private fun chapterOf(page: Int): String? =
        outline?.lastOrNull { it.page in 0..page && it.level <= 1 }?.title

    // --- Marcadores ----------------------------------------------------------------------------

    private fun showBookmarks() {
        val pages = host.bookmarks()
        setRows(
            pages.map { p ->
                Row(page(p), chapterOf(p).orEmpty(), null, 0, onClick = { host.goToPage(p) }, onLongClick = {
                    host.removeBookmark(p)
                    refresh()
                })
            },
            if (pages.isEmpty()) activity.getString(R.string.no_bookmarks) else activity.getString(R.string.bookmarks_hint),
        )
    }

    // --- Resaltados y notas ----------------------------------------------------------------------

    private fun showHighlights() {
        val all = host.annotations().filter {
            it is TextAnnotation || (it is InkAnnotation && it.kind != InkKind.PEN)
        }.sortedWith(compareBy({ it.page }, { it.bounds.top }, { it.bounds.left }))
        updateFilters(all)
        val filter = colorFilter
        val items = if (filter == null) all else all.filter { (it.color or 0xFF000000.toInt()) == filter }

        fun rows() = items.map { a ->
            val kind = when {
                a is TextAnnotation -> activity.getString(R.string.kind_note)
                (a as InkAnnotation).kind == InkKind.UNDERLINE -> activity.getString(R.string.kind_underline)
                else -> activity.getString(R.string.kind_highlight)
            }
            val title = when (a) {
                is TextAnnotation -> a.text
                else -> highlightTexts[a.id]?.ifBlank { null } ?: kind
            }
            val color = when (a) {
                is TextAnnotation -> a.color
                is InkAnnotation -> a.color
            }
            val meaning = host.colorLabel(color)
            val sub = if (meaning.isNotBlank() && a !is TextAnnotation) "${page(a.page)} · $kind · $meaning" else "${page(a.page)} · $kind"
            Row(title, sub, color, 0, { host.goToPage(a.page, a.bounds.top) })
        }

        setRows(rows(), if (items.isEmpty()) activity.getString(R.string.no_highlights) else null)

        // Busca en segundo plano el texto que quedó bajo cada resaltado.
        val missing = items.filter { it is InkAnnotation && it.id !in highlightTexts }
        val text = host.text
        if (missing.isEmpty() || text == null) return
        job?.cancel()
        job = activity.lifecycleScope.launch {
            for (a in missing) {
                val area = android.graphics.RectF(a.bounds)
                if ((a as InkAnnotation).kind == InkKind.UNDERLINE) area.top -= 14f
                val found = withContext(Dispatchers.IO) { runCatching { text.textIn(a.page, area) }.getOrDefault("") }
                highlightTexts[a.id] = found
                if (!coroutineContext.isActive || currentTab != Tab.HIGHLIGHTS) return@launch
            }
            if (currentTab == Tab.HIGHLIGHTS) setRows(rows(), null)
        }
    }

    private fun updateFilters(items: List<Annotation>) {
        val colors = items.map { it.color or 0xFF000000.toInt() }.distinct()
        if (colorFilter != null && colorFilter !in colors) colorFilter = null
        val wanted = listOf<Int?>(null) + colors
        val existing = (0 until filters.childCount).map { (filters.getChildAt(it).tag as? Int?) }
        if (existing == wanted && filters.childCount > 0) return
        filters.removeAllViews()
        for (c in wanted) {
            val chip = Chip(activity)
            chip.tag = c
            chip.isCheckable = true
            chip.text = if (c == null) activity.getString(R.string.filter_all) else host.colorLabel(c).ifBlank {
                activity.getString(R.string.filter_color)
            }
            if (c != null) {
                chip.chipIcon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setSize(36, 36)
                }
            }
            chip.isChecked = c == colorFilter
            chip.setOnClickListener {
                colorFilter = c
                showHighlights()
            }
            filters.addView(chip)
        }
    }

    // --- Búsqueda ------------------------------------------------------------------------------

    private fun showSearchResults() {
        val msg = when {
            lastQuery.isEmpty() -> activity.getString(R.string.search_tip)
            searchHits.isEmpty() -> activity.getString(R.string.search_none, lastQuery)
            else -> activity.getString(R.string.search_count, searchHits.size)
        }
        setRows(searchHits.map { h -> Row(h.snippet, page(h.page), null, 0, { host.showSearchMatch(h) }) }, msg)
    }

    private fun runSearch(query: String) {
        val text = host.text ?: return
        val q = query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        lastQuery = q
        searchHits = emptyList()
        searchProgress.isVisible = true
        val pages = host.pageCount
        setRows(emptyList(), activity.getString(R.string.searching, 0, pages))
        job = activity.lifecycleScope.launch {
            val hits = withContext(Dispatchers.IO) {
                val ctx = coroutineContext
                runCatching {
                    text.search(q, pages, isActive = { ctx.isActive }) { p ->
                        if (p % 10 == 0) status.post {
                            if (lastQuery == q) status.text = activity.getString(R.string.searching, p + 1, pages)
                        }
                    }
                }.getOrDefault(emptyList())
            }
            searchProgress.isVisible = false
            searchHits = hits
            if (currentTab == Tab.SEARCH) showSearchResults()
            if (hits.isEmpty()) {
                // ¿El PDF tiene texto? Si es escaneado, avisamos.
                val hasText = withContext(Dispatchers.IO) {
                    runCatching { (0 until minOf(pages, 15)).any { text.plainText(it).isNotBlank() } }.getOrDefault(true)
                }
                if (!hasText && currentTab == Tab.SEARCH) status.text = activity.getString(R.string.search_scanned)
            }
        }
    }

    // --- Utilidades ----------------------------------------------------------------------------

    private fun page(p: Int) = activity.getString(R.string.page_n, p + 1)

    private fun setRows(rows: List<Row>, message: String?) {
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        status.text = message.orEmpty()
        status.isVisible = !message.isNullOrEmpty()
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        var rows: List<Row> = emptyList()

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val dot: View = v.findViewById(R.id.dot)
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_study, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.title.text = row.title
            holder.subtitle.text = row.subtitle
            holder.subtitle.isVisible = row.subtitle.isNotEmpty()
            val color = row.color
            holder.dot.isVisible = color != null
            if (color != null) {
                holder.dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color or 0xFF000000.toInt())
                }
            }
            val density = holder.itemView.resources.displayMetrics.density
            holder.itemView.setPaddingRelative(
                ((16 + row.indent * 16) * density).toInt(), holder.itemView.paddingTop,
                holder.itemView.paddingEnd, holder.itemView.paddingBottom,
            )
            holder.itemView.setOnClickListener { row.onClick() }
            holder.itemView.setOnLongClickListener(row.onLongClick?.let { cb -> View.OnLongClickListener { cb(); true } })
        }
    }
}
