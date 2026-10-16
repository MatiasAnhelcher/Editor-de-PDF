package com.matias.editorpdf.pdf

import android.graphics.RectF
import android.util.LruCache
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import java.io.StringWriter
import java.text.Normalizer

class OutlineEntry(val title: String, val page: Int, val level: Int)

class SearchHit(val page: Int, val start: Int, val end: Int, val snippet: String)

/** Texto de una página: cada carácter de [text] tiene su caja en [boxes] (vacía para espacios y saltos). */
class PageText(val text: String, val boxes: List<RectF>)

/**
 * Lectura del texto del PDF (para buscar, mostrar lo resaltado e índice del libro).
 * Los PDFs escaneados no tienen texto: en ese caso las búsquedas no encuentran nada.
 * Todas las funciones son lentas: llamarlas fuera del hilo principal.
 */
class StudyText(private val file: File) : Closeable {

    private var doc: PDDocument? = null
    private var closed = false
    private val plain = HashMap<Int, String>()
    private val pages = LruCache<Int, PageText>(8)

    @Synchronized
    private fun doc(): PDDocument {
        check(!closed) { "cerrado" }
        return doc ?: PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).also { doc = it }
    }

    @Synchronized
    fun outline(): List<OutlineEntry> {
        val d = doc()
        val root = d.documentCatalog.documentOutline ?: return emptyList()
        val result = ArrayList<OutlineEntry>()
        fun walk(node: PDOutlineNode, level: Int) {
            for (item in node.children()) {
                if (result.size > 3000) return
                val page = runCatching { item.findDestinationPage(d)?.let { d.pages.indexOf(it) } }.getOrNull() ?: -1
                result.add(OutlineEntry(item.title?.trim().orEmpty().ifEmpty { "—" }, page, level))
                if (level < 6) walk(item, level + 1)
            }
        }
        walk(root, 0)
        return result
    }

    @Synchronized
    fun pageText(page: Int): PageText {
        pages.get(page)?.let { return it }
        val stripper = PositionStripper()
        stripper.sortByPosition = true
        stripper.startPage = page + 1
        stripper.endPage = page + 1
        stripper.writeText(doc(), StringWriter())
        val pt = PageText(stripper.sb.toString(), stripper.boxes)
        pages.put(page, pt)
        plain[page] = pt.text
        return pt
    }

    fun plainText(page: Int): String = synchronized(this) { plain[page] } ?: pageText(page).text

    /** Busca [query] en todo el documento, sin distinguir mayúsculas ni tildes. */
    fun search(query: String, pageCount: Int, isActive: () -> Boolean, onProgress: (Int) -> Unit): List<SearchHit> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        for (p in 0 until pageCount) {
            if (!isActive()) break
            onProgress(p)
            val text = runCatching { plainText(p) }.getOrDefault("")
            val folded = fold(text)
            var from = 0
            while (hits.size < MAX_HITS) {
                val i = folded.indexOf(q, from)
                if (i < 0) break
                val s = (i - 40).coerceAtLeast(0)
                val e = (i + q.length + 60).coerceAtMost(text.length)
                val snippet = (if (s > 0) "…" else "") + text.substring(s, e).replace(Regex("\\s+"), " ").trim() +
                    (if (e < text.length) "…" else "")
                hits.add(SearchHit(p, i, i + q.length, snippet))
                from = i + q.length
            }
            if (hits.size >= MAX_HITS) break
        }
        return hits
    }

    /** Rectángulos (en puntos de la página) que ocupa un resultado de búsqueda, uno por renglón. */
    fun matchRects(hit: SearchHit): List<RectF> {
        val pt = pageText(hit.page)
        val rects = ArrayList<RectF>()
        var current: RectF? = null
        for (i in hit.start until hit.end.coerceAtMost(pt.boxes.size)) {
            val b = pt.boxes[i]
            if (b.isEmpty) continue
            val c = current
            if (c != null && b.centerY() > c.top && b.centerY() < c.bottom) {
                c.union(b)
            } else {
                current = RectF(b).also { rects.add(it) }
            }
        }
        return rects
    }

    /** Texto que queda dentro de [area] (por ejemplo, lo que cubre un resaltado). */
    fun textIn(page: Int, area: RectF): String {
        val pt = pageText(page)
        val sb = StringBuilder()
        var lastIncluded = -2
        for (i in pt.text.indices) {
            val b = pt.boxes[i]
            if (b.isEmpty) {
                if (lastIncluded == i - 1 && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                continue
            }
            if (area.contains(b.centerX(), b.centerY())) {
                if (lastIncluded >= 0 && lastIncluded < i - 1 && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                sb.append(pt.text[i])
                lastIncluded = i
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    @Synchronized
    override fun close() {
        closed = true
        runCatching { doc?.close() }
        doc = null
    }

    private class PositionStripper : PDFTextStripper() {
        val sb = StringBuilder()
        val boxes = ArrayList<RectF>()

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            for (tp in textPositions.orEmpty()) {
                val u = tp.unicode ?: continue
                val h = maxOf(tp.heightDir, tp.fontSizeInPt * 0.7f)
                val box = RectF(tp.xDirAdj, tp.yDirAdj - h, tp.xDirAdj + tp.widthDirAdj, tp.yDirAdj + h * 0.15f)
                for (ch in u) {
                    sb.append(ch)
                    boxes.add(box)
                }
            }
        }

        override fun writeWordSeparator() {
            sb.append(' ')
            boxes.add(RectF())
        }

        override fun writeLineSeparator() {
            sb.append('\n')
            boxes.add(RectF())
        }
    }

    companion object {
        private const val MAX_HITS = 500

        /** Minúsculas y sin tildes, manteniendo la misma cantidad de caracteres. */
        fun fold(s: String): String {
            val out = CharArray(s.length)
            for (i in s.indices) {
                val c = s[i]
                out[i] = if (c.code < 128) {
                    c.lowercaseChar()
                } else {
                    Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull()?.lowercaseChar() ?: c
                }
                if (out[i] == '\n' || out[i] == '\t') out[i] = ' '
            }
            return String(out)
        }
    }
}
