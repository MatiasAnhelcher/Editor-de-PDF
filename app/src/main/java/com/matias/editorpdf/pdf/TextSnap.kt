package com.matias.editorpdf.pdf

import android.graphics.RectF
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.InkAnnotation
import com.matias.editorpdf.model.InkKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Ajusta un resaltado o subrayado recto a las palabras del PDF que tiene debajo. */
object TextSnap {

    /** Devuelve los trazos ajustados (uno por renglón) o null si no hay texto cerca. Lento: fuera del hilo principal. */
    fun snap(text: StudyText, a: InkAnnotation): List<InkAnnotation>? {
        if (a.pointCount < 2 || a.kind == InkKind.PEN || a.kind == InkKind.SHAPE) return null
        val pt = text.pageText(a.page)
        if (pt.boxes.none { !it.isEmpty }) return null
        // El subrayado se dibuja debajo del renglón: buscamos el texto un poco más arriba.
        val lift = if (a.kind == InkKind.UNDERLINE) 6f else 0f
        val last = a.pointCount - 1
        val s = nearest(pt, a.x(0), a.y(0) - lift) ?: return null
        val e = nearest(pt, a.x(last), a.y(last) - lift) ?: return null
        val from = minOf(s, e)
        val to = maxOf(s, e)

        val lines = ArrayList<RectF>()
        for (i in from..to) {
            val b = pt.boxes[i]
            if (b.isEmpty) continue
            val cur = lines.lastOrNull()
            if (cur != null && abs(b.centerY() - cur.centerY()) < cur.height() * 0.5f && b.left >= cur.left - 2f) {
                cur.union(b)
            } else {
                lines.add(RectF(b))
            }
        }
        if (lines.isEmpty()) return null
        return lines.mapIndexed { i, r ->
            val id = if (i == 0) a.id else Annotation.newId()
            if (a.kind == InkKind.HIGHLIGHTER) {
                val y = r.centerY()
                InkAnnotation(id, a.page, a.kind, a.color, max(r.height() * 1.05f, 4f),
                    floatArrayOf(r.left, y, 0.5f, r.right, y, 0.5f), true)
            } else if (a.kind == InkKind.STRIKE) {
                // El tachado cruza el renglón un poco por debajo de la mitad (la altura de las minúsculas).
                val y = r.top + r.height() * 0.56f
                InkAnnotation(id, a.page, a.kind, a.color, a.width,
                    floatArrayOf(r.left, y, 0.5f, r.right, y, 0.5f), true)
            } else {
                val y = r.bottom + a.width / 2f + 0.5f
                InkAnnotation(id, a.page, a.kind, a.color, a.width,
                    floatArrayOf(r.left, y, 0.5f, r.right, y, 0.5f), true)
            }
        }
    }

    private fun nearest(pt: PageText, x: Float, y: Float): Int? {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in pt.boxes.indices) {
            val b = pt.boxes[i]
            if (b.isEmpty) continue
            val dx = when {
                x < b.left -> b.left - x
                x > b.right -> x - b.right
                else -> 0f
            }
            val dy = when {
                y < b.top -> b.top - y
                y > b.bottom -> y - b.bottom
                else -> 0f
            }
            val d = hypot(dx, dy * 2f)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (best >= 0 && bestDist < 14f) best else null
    }
}

/**
 * Lo mismo que [TextSnap] pero mirando la imagen de la página: sirve para PDFs escaneados.
 * Busca el renglón (franja horizontal con tinta) más cercano al trazo y lo ajusta a sus palabras.
 */
object ImageSnap {

    private const val SCALE = 3f

    fun snap(renderer: PageRenderer, a: InkAnnotation, pageWidth: Float, pageHeight: Float): List<InkAnnotation>? {
        if (a.pointCount < 2 || a.kind == InkKind.PEN || a.kind == InkKind.SHAPE) return null
        val last = a.pointCount - 1
        val lift = if (a.kind == InkKind.UNDERLINE) 5f else 0f
        val sx0 = minOf(a.x(0), a.x(last))
        val sx1 = maxOf(a.x(0), a.x(last))
        val yMid = (a.y(0) + a.y(last)) / 2f - lift
        val region = RectF(
            (sx0 - 60f).coerceAtLeast(0f), (yMid - 26f).coerceAtLeast(0f),
            (sx1 + 60f).coerceAtMost(pageWidth), (yMid + 26f).coerceAtMost(pageHeight),
        )
        if (region.width() < 2f || region.height() < 2f) return null
        val bmp = renderer.renderSync(a.page, region, SCALE) ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val dark = BooleanArray(w * h) { i ->
            val c = px[i]
            val lum = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            lum < 150
        }

        // Filas con tinta dentro del ancho del trazo.
        val c0 = ((sx0 - region.left) * SCALE).toInt().coerceIn(0, w - 1)
        val c1 = ((sx1 - region.left) * SCALE).toInt().coerceIn(c0, w - 1)
        val span = c1 - c0 + 1
        val threshold = maxOf(2, (span * 0.015f).toInt())
        val rowInk = IntArray(h) { y -> (c0..c1).count { x -> dark[y * w + x] } }

        // Franjas de renglón (permitiendo cortes de 1-2 px).
        class Band(val top: Int, val bottom: Int)
        val bands = ArrayList<Band>()
        var y = 0
        while (y < h) {
            if (rowInk[y] < threshold) {
                y++
                continue
            }
            val top = y
            var bottom = y
            var gap = 0
            while (y < h && gap <= 2) {
                if (rowInk[y] >= threshold) {
                    bottom = y
                    gap = 0
                } else {
                    gap++
                }
                y++
            }
            val height = bottom - top + 1
            if (height >= 4 && height <= 40 * SCALE) bands.add(Band(top, bottom))
        }
        val target = (yMid - region.top) * SCALE
        val band = bands.minByOrNull { b ->
            when {
                target < b.top -> b.top - target
                target > b.bottom -> target - b.bottom
                else -> 0f
            }
        } ?: return null
        val dist = when {
            target < band.top -> band.top - target
            target > band.bottom -> target - band.bottom
            else -> 0f
        }
        if (dist > 12f * SCALE) return null

        // Columnas con tinta en ese renglón: recortamos al texto y completamos la palabra tocada.
        val colInk = BooleanArray(w) { x -> (band.top..band.bottom).any { yy -> dark[yy * w + x] } }
        var left = (c0..c1).firstOrNull { colInk[it] } ?: return null
        var right = (c1 downTo c0).firstOrNull { colInk[it] } ?: return null
        val wordGap = maxOf(3, ((band.bottom - band.top + 1) * 0.22f).toInt())
        while (true) {
            val next = (left - 1 downTo maxOf(0, left - wordGap)).firstOrNull { colInk[it] } ?: break
            left = next
        }
        while (true) {
            val next = (right + 1..minOf(w - 1, right + wordGap)).firstOrNull { colInk[it] } ?: break
            right = next
        }

        val l = region.left + left / SCALE
        val r = region.left + (right + 1) / SCALE
        val t = region.top + band.top / SCALE
        val b = region.top + (band.bottom + 1) / SCALE
        val snapped = if (a.kind == InkKind.HIGHLIGHTER) {
            val cy = (t + b) / 2f
            InkAnnotation(a.id, a.page, a.kind, a.color, max((b - t) * 1.15f + 1f, 4f),
                floatArrayOf(l - 1f, cy, 0.5f, r + 1f, cy, 0.5f), true)
        } else if (a.kind == InkKind.STRIKE) {
            val sy = t + (b - t) * 0.56f
            InkAnnotation(a.id, a.page, a.kind, a.color, a.width,
                floatArrayOf(l, sy, 0.5f, r, sy, 0.5f), true)
        } else {
            val uy = b + a.width / 2f + 0.8f
            InkAnnotation(a.id, a.page, a.kind, a.color, a.width,
                floatArrayOf(l, uy, 0.5f, r, uy, 0.5f), true)
        }
        return listOf(snapped)
    }
}
