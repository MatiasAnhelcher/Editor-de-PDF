package com.matias.editorpdf

import android.content.Context
import android.net.Uri
import com.matias.editorpdf.model.Tool
import org.json.JSONArray
import org.json.JSONObject

/** Preferencias de las herramientas (color, grosor, etc.). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tool: Tool
        get() = runCatching { Tool.valueOf(prefs.getString("tool", null) ?: "PEN") }.getOrDefault(Tool.PEN)
        set(v) = prefs.edit().putString("tool", v.name).apply()

    /** true: solo el lápiz (S Pen) escribe y el dedo sirve para moverse y hacer zoom. */
    var stylusOnly: Boolean
        get() = prefs.getBoolean("stylus_only", true)
        set(v) = prefs.edit().putBoolean("stylus_only", v).apply()

    var straightHighlighter: Boolean
        get() = prefs.getBoolean("straight_highlighter", true)
        set(v) = prefs.edit().putBoolean("straight_highlighter", v).apply()

    /** Ajustar resaltados y subrayados rectos al texto del PDF. */
    var snapToText: Boolean
        get() = prefs.getBoolean("snap_to_text", true)
        set(v) = prefs.edit().putBoolean("snap_to_text", v).apply()

    /** Qué significa cada color (por ejemplo, amarillo = Importante). */
    fun colorLabel(color: Int): String =
        prefs.getString("label_${color or 0xFF000000.toInt()}", null) ?: DEFAULT_LABELS[color or 0xFF000000.toInt()].orEmpty()

    fun setColorLabel(color: Int, label: String) =
        prefs.edit().putString("label_${color or 0xFF000000.toInt()}", label.trim()).apply()

    fun color(tool: Tool): Int = prefs.getInt("color_${tool.name}", palette(tool).first())
    fun setColor(tool: Tool, color: Int) = prefs.edit().putInt("color_${tool.name}", color).apply()

    fun width(tool: Tool): Float = prefs.getFloat("width_${tool.name}", range(tool).default)
        .coerceIn(range(tool).min, range(tool).max)

    fun setWidth(tool: Tool, w: Float) = prefs.edit().putFloat("width_${tool.name}", w).apply()

    class Range(val min: Float, val max: Float, val default: Float)

    companion object {
        fun palette(tool: Tool): List<Int> = when (tool) {
            Tool.HIGHLIGHTER -> HIGHLIGHT_COLORS
            Tool.UNDERLINE -> listOf(0xFFD32F2F, 0xFF1565C0, 0xFF000000, 0xFF2E7D32, 0xFF6A1B9A, 0xFFEF6C00).map { it.toInt() }
            Tool.LASSO -> PEN_COLORS + HIGHLIGHT_COLORS
            else -> PEN_COLORS
        }

        private val PEN_COLORS = listOf(0xFF000000, 0xFF1A237E, 0xFFC62828, 0xFF2E7D32, 0xFF6A1B9A, 0xFFEF6C00).map { it.toInt() }
        private val HIGHLIGHT_COLORS =
            listOf(0xFFFFEB3B, 0xFF76FF03, 0xFF18FFFF, 0xFFFF80AB, 0xFFFFAB40, 0xFFB388FF).map { it.toInt() }

        private val DEFAULT_LABELS: Map<Int, String> = mapOf(
            0xFFFFEB3B to "Importante",
            0xFF76FF03 to "Definición",
            0xFF18FFFF to "Ejemplo",
            0xFFFF80AB to "Duda",
            0xFFFFAB40 to "Dato o fecha",
            0xFFB388FF to "Idea propia",
        ).mapKeys { it.key.toInt() }

        fun range(tool: Tool): Range = when (tool) {
            Tool.HIGHLIGHTER -> Range(4f, 24f, 12f)
            Tool.UNDERLINE -> Range(0.5f, 4f, 1.5f)
            Tool.TEXT -> Range(6f, 36f, 12f)
            else -> Range(0.5f, 6f, 1.6f)
        }
    }
}

data class RecentDoc(val uri: String, val name: String, val time: Long, val page: Int)

/** Lista de PDFs abiertos recientemente, con la última página leída. */
class Recents(context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun list(): List<RecentDoc> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RecentDoc(o.getString("uri"), o.optString("name"), o.optLong("time"), o.optInt("page"))
        }
    }.getOrDefault(emptyList())

    fun touch(uri: Uri, name: String) {
        val old = find(uri)
        save(listOf(RecentDoc(uri.toString(), name, System.currentTimeMillis(), old?.page ?: 0)) + others(uri))
    }

    fun setPage(uri: Uri, page: Int) {
        val old = find(uri) ?: return
        save(listOf(old.copy(page = page, time = System.currentTimeMillis())) + others(uri))
    }

    fun lastPage(uri: Uri): Int = find(uri)?.page ?: 0

    fun remove(uri: String) = save(list().filter { it.uri != uri })

    private fun find(uri: Uri) = list().firstOrNull { it.uri == uri.toString() }
    private fun others(uri: Uri) = list().filter { it.uri != uri.toString() }

    private fun save(items: List<RecentDoc>) {
        val arr = JSONArray()
        items.take(40).forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.time).put("page", it.page))
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}

/** Páginas marcadas de cada PDF. */
class Bookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun list(uri: Uri): List<Int> = runCatching {
        val arr = JSONArray(prefs.getString(uri.toString(), "[]"))
        (0 until arr.length()).map { arr.getInt(it) }.sorted()
    }.getOrDefault(emptyList())

    fun contains(uri: Uri, page: Int) = page in list(uri)

    /** Marca o desmarca la página. Devuelve true si quedó marcada. */
    fun toggle(uri: Uri, page: Int): Boolean {
        val current = list(uri).toMutableSet()
        val added = current.add(page)
        if (!added) current.remove(page)
        save(uri, current)
        return added
    }

    fun remove(uri: Uri, page: Int) = save(uri, list(uri).toSet() - page)

    private fun save(uri: Uri, pages: Set<Int>) {
        val arr = JSONArray()
        pages.sorted().forEach { arr.put(it) }
        prefs.edit().putString(uri.toString(), arr.toString()).apply()
    }
}
