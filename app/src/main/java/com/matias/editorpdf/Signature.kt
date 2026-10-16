package com.matias.editorpdf

import android.content.Context
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.InkAnnotation
import com.matias.editorpdf.model.InkKind
import org.json.JSONArray

/** La firma guardada: trazos normalizados (0..1 en la caja de la firma) y su proporción ancho/alto. */
class Signature(val strokes: List<FloatArray>, val aspect: Float) {

    /** Crea los trazos para poner la firma centrada en (cx, cy) con [width] puntos de ancho. */
    fun place(page: Int, cx: Float, cy: Float, width: Float, color: Int): List<InkAnnotation> {
        val height = width / aspect
        val left = cx - width / 2f
        val top = cy - height / 2f
        return strokes.map { s ->
            val n = s.size / 2
            val pts = FloatArray(n * 3)
            for (i in 0 until n) {
                pts[i * 3] = left + s[i * 2] * width
                pts[i * 3 + 1] = top + s[i * 2 + 1] * height
                pts[i * 3 + 2] = 0.6f
            }
            InkAnnotation(Annotation.newId(), page, InkKind.PEN, color, 1.3f, pts, false)
        }
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("signature", Context.MODE_PRIVATE)

        fun load(context: Context): Signature? = runCatching {
            val p = prefs(context)
            val raw = p.getString("strokes", null) ?: return null
            val arr = JSONArray(raw)
            val strokes = (0 until arr.length()).map { i ->
                val s = arr.getJSONArray(i)
                FloatArray(s.length()) { s.getDouble(it).toFloat() }
            }
            if (strokes.isEmpty()) null else Signature(strokes, p.getFloat("aspect", 3f))
        }.getOrNull()

        fun clear(context: Context) = prefs(context).edit().clear().apply()

        /** Guarda trazos en coordenadas de pantalla, normalizándolos a su caja. */
        fun save(context: Context, raw: List<FloatArray>): Signature? {
            if (raw.isEmpty()) return null
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (s in raw) {
                var i = 0
                while (i + 1 < s.size) {
                    minX = minOf(minX, s[i]); maxX = maxOf(maxX, s[i])
                    minY = minOf(minY, s[i + 1]); maxY = maxOf(maxY, s[i + 1])
                    i += 2
                }
            }
            val w = (maxX - minX).coerceAtLeast(1f)
            val h = (maxY - minY).coerceAtLeast(1f)
            // Se guarda con un poquito más de cuatro decimales: alcanza para que se vea igual.
            val norm = raw.map { s -> FloatArray(s.size) { i -> if (i % 2 == 0) (s[i] - minX) / w else (s[i] - minY) / h } }
            val arr = JSONArray()
            for (s in norm) {
                val a = JSONArray()
                for (v in s) a.put(Math.round(v * 10000.0) / 10000.0)
                arr.put(a)
            }
            prefs(context).edit().putString("strokes", arr.toString()).putFloat("aspect", w / h).apply()
            return Signature(norm, w / h)
        }
    }
}
