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

enum class Tool { HAND, PEN, HIGHLIGHTER, UNDERLINE, TEXT, ERASER, LASSO, STRIKE, SHAPE, NOTE }

enum class InkKind { PEN, HIGHLIGHTER, UNDERLINE, STRIKE, SHAPE }

/** Formas que dibuja la herramienta Formas. Se guardan como trazos (InkAnnotation de tipo SHAPE). */
enum class ShapeType {
    RECT, ELLIPSE, LINE, ARROW;

    /** Puntos (x, y, presión) del contorno de la forma que va de (x0, y0) a (x1, y1). */
    fun points(x0: Float, y0: Float, x1: Float, y1: Float, width: Float): FloatArray {
        val out = ArrayList<Float>()
        fun add(x: Float, y: Float) {
            out.add(x); out.add(y); out.add(0.5f)
        }
        when (this) {
            RECT -> {
                add(x0, y0); add(x1, y0); add(x1, y1); add(x0, y1); add(x0, y0)
            }
            ELLIPSE -> {
                val cx = (x0 + x1) / 2f
                val cy = (y0 + y1) / 2f
                val rx = kotlin.math.abs(x1 - x0) / 2f
                val ry = kotlin.math.abs(y1 - y0) / 2f
                val n = 72
                for (i in 0..n) {
                    val t = (2.0 * Math.PI * i / n)
                    add(cx + rx * kotlin.math.cos(t).toFloat(), cy + ry * kotlin.math.sin(t).toFloat())
                }
            }
            LINE -> {
                add(x0, y0); add(x1, y1)
            }
            ARROW -> {
                // Cuerpo hasta la punta y, desde la punta, las dos alas (un solo trazo continuo).
                val len = hypot(x1 - x0, y1 - y0).coerceAtLeast(0.01f)
                val ux = (x1 - x0) / len
                val uy = (y1 - y0) / len
                val head = min(max(8f, width * 4.5f), len * 0.45f)
                val wing = head * 0.6f
                val bx = x1 - ux * head
                val by = y1 - uy * head
                add(x0, y0); add(x1, y1)
                add(bx - uy * wing, by + ux * wing)
                add(x1, y1)
                add(bx + uy * wing, by - ux * wing)
            }
        }
        return out.toFloatArray()
    }
}

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

    /** Zona donde está el texto marcado (el subrayado queda debajo y el tachado en el medio del renglón). */
    fun textArea(): RectF = RectF(bounds).apply {
        when (kind) {
            InkKind.UNDERLINE -> top -= 14f
            InkKind.STRIKE -> inset(0f, -7f)
            else -> Unit
        }
    }

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

/**
 * Cuadro de texto sobre la página. (x, y) es la esquina superior izquierda y [boxWidth] el ancho
 * del cuadro: el texto se acomoda solo en renglones dentro de ese ancho (0 = sin cuadro, notas viejas).
 */
class TextAnnotation(
    override val id: String,
    override val page: Int,
    val x: Float,
    val y: Float,
    val text: String,
    override val color: Int,
    val size: Float,
    val boxWidth: Float = 0f,
    /** Nota adhesiva: se ve como un iconito y el texto se abre al tocarla (como los comentarios de Acrobat). */
    val sticky: Boolean = false,
) : Annotation() {

    /** Renglones ya cortados para que entren en el ancho del cuadro. */
    val lines: List<String> by lazy { wrap(text, size, boxWidth) }

    fun baseline(line: Int): Float = y + size * BASELINE + line * size * LINE_SPACING

    override val bounds: RectF by lazy {
        val ls = lines
        if (sticky) return@lazy RectF(x, y, x + NOTE_SIZE, y + NOTE_SIZE)
        val w = if (boxWidth > 0f) boxWidth else synchronized(measurePaint) {
            measurePaint.textSize = size
            ls.maxOf { measurePaint.measureText(it) }
        }
        RectF(x, y, x + max(w, size * 0.5f), y + (ls.size - 1) * size * LINE_SPACING + size * 1.2f)
    }

    override fun hitTest(x: Float, y: Float, radius: Float): Boolean {
        val b = bounds
        return x >= b.left - radius && x <= b.right + radius && y >= b.top - radius && y <= b.bottom + radius
    }

    fun moved(dx: Float, dy: Float) = TextAnnotation(id, page, x + dx, y + dy, text, color, size, boxWidth, sticky)
    fun withText(newText: String) = TextAnnotation(id, page, x, y, newText, color, size, boxWidth, sticky)
    fun withColor(c: Int) = TextAnnotation(id, page, x, y, text, c, size, boxWidth, sticky)
    fun withBoxWidth(w: Float) = TextAnnotation(id, page, x, y, text, color, size, w, sticky)
    override fun withPage(page: Int) = TextAnnotation(id, page, x, y, text, color, size, boxWidth, sticky)

    override fun toJson(): JSONObject = JSONObject().apply {
        put("type", "text")
        put("id", id)
        put("page", page)
        put("x", round2(x))
        put("y", round2(y))
        put("text", text)
        put("color", color)
        put("size", round2(size))
        if (boxWidth > 0f) put("bw", round2(boxWidth))
        if (sticky) put("sticky", true)
    }

    companion object {
        const val LINE_SPACING = 1.25f

        /** Lado del iconito de una nota adhesiva, en puntos. */
        const val NOTE_SIZE = 20f
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
            boxWidth = o.optDouble("bw", 0.0).toFloat(),
            sticky = o.optBoolean("sticky", false),
        )

        /** Corta el texto en renglones de hasta [width] puntos, respetando palabras y saltos de línea. */
        fun wrap(text: String, size: Float, width: Float): List<String> {
            val paragraphs = text.split('\n')
            if (width <= 0f) return paragraphs
            return synchronized(measurePaint) {
                measurePaint.textSize = size
                val out = ArrayList<String>()
                for (para in paragraphs) {
                    if (para.isEmpty()) {
                        out.add("")
                        continue
                    }
                    var line = StringBuilder()
                    for (word in para.split(' ')) {
                        val candidate = if (line.isEmpty()) word else "$line $word"
                        if (measurePaint.measureText(candidate) <= width) {
                            line = StringBuilder(candidate)
                            continue
                        }
                        if (line.isNotEmpty()) out.add(line.toString())
                        // Palabra más larga que el cuadro: se corta por letras.
                        var rest = word
                        while (rest.isNotEmpty() && measurePaint.measureText(rest) > width) {
                            var n = measurePaint.breakText(rest, true, width, null).coerceAtLeast(1)
                            if (n >= rest.length) n = rest.length
                            out.add(rest.substring(0, n))
                            rest = rest.substring(n)
                        }
                        line = StringBuilder(rest)
                    }
                    out.add(line.toString())
                }
                out
            }
        }
    }
}
