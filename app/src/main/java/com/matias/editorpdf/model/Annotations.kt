package com.matias.editorpdf.model

import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Tool { HAND, PEN, HIGHLIGHTER, UNDERLINE, TEXT, ERASER, LASSO }

enum class InkKind { PEN, HIGHLIGHTER, UNDERLINE }

/**
 * Una anotación sobre una página. Las coordenadas están en puntos PDF, relativas a la página
 * tal como se ve en pantalla (origen arriba a la izquierda, rotación ya aplicada).
 * Los objetos son inmutables: editar = reemplazar por uno nuevo con el mismo id.
 */
sealed class Annotation {
    abstract val id: String
    abstract val page: Int
    abstract val bounds: RectF
    abstract fun hitTest(x: Float, y: Float, radius: Float): Boolean
    abstract fun toJson(): JSONObject
    abstract fun withPage(page: Int): Annotation

    fun moveBy(dx: Float, dy: Float): Annotation = when (this) {
        is InkAnnotation -> translated(dx, dy)
        is TextAnnotation -> moved(dx, dy)
    }

    fun recolored(c: Int): Annotation = when (this) {
        is InkAnnotation -> withColor(c)
        is TextAnnotation -> withColor(c)
    }

    abstract val color: Int

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject): Annotation? = when (o.optString("type")) {
            "ink" -> InkAnnotation.fromJson(o)
            "text" -> TextAnnotation.fromJson(o)
            else -> null
        }

        fun listToJson(list: List<Annotation>): JSONArray =
            JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(arr: JSONArray): List<Annotation> =
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }

        internal fun round2(v: Float): Double = (v * 100f).roundToInt() / 100.0
    }
}

/** Trazo de lápiz, resaltador o subrayado. [points] guarda tríos x, y, presión. */
class InkAnnotation(
    override val id: String,
    override val page: Int,
    val kind: InkKind,
    override val color: Int,
    val width: Float,
    val points: FloatArray,
    val straight: Boolean,
) : Annotation() {

    class Run(val width: Float, val from: Int, val to: Int)

    val pointCount: Int get() = points.size / 3
    fun x(i: Int): Float = points[i * 3]
    fun y(i: Int): Float = points[i * 3 + 1]
    fun p(i: Int): Float = points[i * 3 + 2]

    fun widthAt(pressure: Float): Float =
        if (kind == InkKind.PEN) width * (0.35f + 0.9f * pressure.coerceIn(0f, 1f)) else width

    val maxWidth: Float get() = if (kind == InkKind.PEN) width * 1.25f else width

    /** Índices de los puntos que se dibujan (un trazo recto solo usa el primero y el último). */
    fun drawnIndices(): IntArray = when {
        pointCount == 0 -> IntArray(0)
        straight && pointCount > 1 -> intArrayOf(0, pointCount - 1)
        else -> IntArray(pointCount) { it }
    }

    /** Agrupa los segmentos del lápiz por grosor (según la presión) para dibujarlos igual en pantalla y en el PDF. */
    fun penRuns(): List<Run> {
        val n = pointCount
        if (n < 2) return listOf(Run(quantize(widthAt(if (n == 1) p(0) else 0.5f)), 0, 0))
        val runs = ArrayList<Run>()
        var start = 0
        var current = quantize(widthAt((p(0) + p(1)) / 2f))
        for (i in 2 until n) {
            val w = quantize(widthAt((p(i - 1) + p(i)) / 2f))
            if (w != current) {
                runs.add(Run(current, start, i - 1))
                start = i - 1
                current = w
            }
        }
        runs.add(Run(current, start, n - 1))
        return runs
    }

    override val bounds: RectF by lazy {
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in drawnIndices()) {
            r.left = min(r.left, x(i)); r.right = max(r.right, x(i))
            r.top = min(r.top, y(i)); r.bottom = max(r.bottom, y(i))
        }
        if (r.left > r.right) r.set(0f, 0f, 0f, 0f)
        val pad = maxWidth / 2f
        r.inset(-pad, -pad)
        r
    }

    override fun hitTest(x: Float, y: Float, radius: Float): Boolean {
        val b = bounds
        if (x < b.left - radius || x > b.right + radius || y < b.top - radius || y > b.bottom + radius) return false
        val idx = drawnIndices()
        val tolerance = radius + maxWidth / 2f
        if (idx.size == 1) return hypot(x - x(idx[0]), y - y(idx[0])) <= tolerance
        for (k in 1 until idx.size) {
            val a = idx[k - 1]
            val c = idx[k]
            if (distToSegment(x, y, x(a), y(a), x(c), y(c)) <= tolerance) return true
        }
        return false
    }

    override fun withPage(page: Int) = InkAnnotation(id, page, kind, color, width, points, straight)

    fun withColor(c: Int) = InkAnnotation(id, page, kind, c, width, points, straight)

    fun translated(dx: Float, dy: Float): InkAnnotation {
        val p = points.copyOf()
        for (i in 0 until pointCount) {
            p[i * 3] += dx
            p[i * 3 + 1] += dy
        }
        return InkAnnotation(id, page, kind, color, width, p, straight)
    }

    override fun toJson(): JSONObject = JSONObject().apply {
        put("type", "ink")
        put("id", id)
        put("page", page)
        put("kind", kind.name)
        put("color", color)
        put("width", round2(width))
        put("straight", straight)
        val arr = JSONArray()
        for (v in points) arr.put(round2(v))
        put("pts", arr)
    }

    companion object {
        private fun quantize(w: Float): Float = (w * 8f).roundToInt() / 8f

        fun fromJson(o: JSONObject): InkAnnotation {
            val arr = o.getJSONArray("pts")
            val pts = FloatArray(arr.length() - arr.length() % 3) { arr.getDouble(it).toFloat() }
            return InkAnnotation(
                id = o.optString("id").ifEmpty { newId() },
                page = o.optInt("page", 0),
                kind = runCatching { InkKind.valueOf(o.getString("kind")) }.getOrDefault(InkKind.PEN),
                color = o.optInt("color", 0xFF000000.toInt()),
                width = o.optDouble("width", 1.5).toFloat(),
                points = pts,
                straight = o.optBoolean("straight", false),
            )
        }

        private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }
    }
}

/** Texto escrito sobre la página. (x, y) es la esquina superior izquierda. */
class TextAnnotation(
    override val id: String,
    override val page: Int,
    val x: Float,
    val y: Float,
    val text: String,
    override val color: Int,
    val size: Float,
) : Annotation() {

    val lines: List<String> get() = text.split('\n')

    fun baseline(line: Int): Float = y + size * BASELINE + line * size * LINE_SPACING

    override val bounds: RectF by lazy {
        val ls = lines
        val w = synchronized(measurePaint) {
            measurePaint.textSize = size
            ls.maxOf { measurePaint.measureText(it) }
        }
        RectF(x, y, x + max(w, size * 0.5f), y + (ls.size - 1) * size * LINE_SPACING + size * 1.2f)
    }

    override fun hitTest(x: Float, y: Float, radius: Float): Boolean {
        val b = bounds
        return x >= b.left - radius && x <= b.right + radius && y >= b.top - radius && y <= b.bottom + radius
    }

    fun moved(dx: Float, dy: Float) = TextAnnotation(id, page, x + dx, y + dy, text, color, size)
    fun withText(newText: String) = TextAnnotation(id, page, x, y, newText, color, size)
    fun withColor(c: Int) = TextAnnotation(id, page, x, y, text, c, size)
    override fun withPage(page: Int) = TextAnnotation(id, page, x, y, text, color, size)

    override fun toJson(): JSONObject = JSONObject().apply {
        put("type", "text")
        put("id", id)
        put("page", page)
        put("x", round2(x))
        put("y", round2(y))
        put("text", text)
        put("color", color)
        put("size", round2(size))
    }

    companion object {
        const val LINE_SPACING = 1.25f
        const val BASELINE = 0.95f
        private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

        fun fromJson(o: JSONObject) = TextAnnotation(
            id = o.optString("id").ifEmpty { newId() },
            page = o.optInt("page", 0),
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
            text = o.optString("text"),
            color = o.optInt("color", 0xFF000000.toInt()),
            size = o.optDouble("size", 12.0).toFloat(),
        )
    }
}
