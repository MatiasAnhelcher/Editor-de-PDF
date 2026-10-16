package com.matias.editorpdf.view

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.InkAnnotation
import com.matias.editorpdf.model.InkKind
import com.matias.editorpdf.model.TextAnnotation
import com.matias.editorpdf.pdf.PdfAnnotationIO
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/** Dibuja anotaciones sobre un Canvas en coordenadas de página (puntos). Usar desde un solo hilo. */
class AnnotationPainter {

    private class InkRender(val paths: List<Path>, val widths: FloatArray)

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val coverTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5D4037.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val inkCache = WeakHashMap<InkAnnotation, InkRender>()

    fun draw(canvas: Canvas, a: Annotation, cache: Boolean = true) {
        when (a) {
            is InkAnnotation -> drawInk(canvas, a, cache)
            is TextAnnotation -> {
                textPaint.color = a.color or 0xFF000000.toInt()
                textPaint.textSize = a.size
                a.lines.forEachIndexed { i, line -> canvas.drawText(line, a.x, a.baseline(i), textPaint) }
            }
        }
    }

    /** Tapa la anotación (modo repaso / tarjetas). Devuelve false si esa anotación no se tapa. */
    fun drawCover(canvas: Canvas, a: Annotation): Boolean {
        if (a is InkAnnotation && a.kind == InkKind.HIGHLIGHTER) {
            // Mismo trazo pero opaco: tapa el texto resaltado.
            val r = inkCache.getOrPut(a) { buildInkRender(a) }
            inkPaint.strokeJoin = Paint.Join.ROUND
            inkPaint.strokeCap = if (a.straight) Paint.Cap.BUTT else Paint.Cap.ROUND
            inkPaint.color = a.color or 0xFF000000.toInt()
            inkPaint.blendMode = null
            r.paths.forEachIndexed { i, p ->
                inkPaint.strokeWidth = r.widths[i] * 1.15f
                canvas.drawPath(p, inkPaint)
            }
            return true
        }
        val rect = coverRect(a) ?: return false
        coverPaint.color = if (a is TextAnnotation) 0xFFFFF59D.toInt() else 0xFFCFD8DC.toInt()
        canvas.drawRoundRect(rect, 2f, 2f, coverPaint)
        coverTextPaint.textSize = min(rect.height() * 0.8f, 14f)
        canvas.drawText("?", rect.centerX(), rect.centerY() + coverTextPaint.textSize * 0.35f, coverTextPaint)
        return true
    }

    private fun drawInk(canvas: Canvas, a: InkAnnotation, cache: Boolean) {
        if (a.pointCount == 0) return
        val r = if (cache) inkCache.getOrPut(a) { buildInkRender(a) } else buildInkRender(a)
        inkPaint.strokeJoin = Paint.Join.ROUND
        inkPaint.strokeCap = if (a.kind == InkKind.HIGHLIGHTER && a.straight) Paint.Cap.BUTT else Paint.Cap.ROUND
        if (a.kind == InkKind.HIGHLIGHTER) {
            val alpha = (PdfAnnotationIO.HIGHLIGHT_ALPHA * 255).roundToInt()
            inkPaint.color = (a.color and 0x00FFFFFF) or (alpha shl 24)
            inkPaint.blendMode = BlendMode.MULTIPLY
        } else {
            inkPaint.color = a.color or 0xFF000000.toInt()
            inkPaint.blendMode = null
        }
        r.paths.forEachIndexed { i, p ->
            inkPaint.strokeWidth = r.widths[i]
            canvas.drawPath(p, inkPaint)
        }
        inkPaint.blendMode = null
    }

    private fun buildInkRender(a: InkAnnotation): InkRender {
        if (a.kind == InkKind.PEN) {
            val runs = a.penRuns()
            val paths = runs.map { run ->
                Path().apply {
                    moveTo(a.x(run.from), a.y(run.from))
                    if (run.from == run.to) lineTo(a.x(run.from) + 0.01f, a.y(run.from))
                    else for (i in run.from + 1..run.to) lineTo(a.x(i), a.y(i))
                }
            }
            return InkRender(paths, FloatArray(runs.size) { runs[it].width })
        }
        val idx = a.drawnIndices()
        val path = Path().apply {
            moveTo(a.x(idx[0]), a.y(idx[0]))
            if (idx.size == 1) lineTo(a.x(idx[0]) + 0.01f, a.y(idx[0]))
            for (k in 1 until idx.size) lineTo(a.x(idx[k]), a.y(idx[k]))
        }
        return InkRender(listOf(path), floatArrayOf(a.width))
    }

    companion object {
        /** Zona que se tapa en el modo repaso (null = no se tapa, como el lápiz). */
        fun coverRect(a: Annotation): RectF? = when {
            a is TextAnnotation -> RectF(a.bounds).apply { inset(-2f, -2f) }
            a is InkAnnotation && a.kind == InkKind.UNDERLINE -> RectF(a.bounds).apply { top -= 13f }
            a is InkAnnotation && a.kind == InkKind.HIGHLIGHTER -> RectF(a.bounds)
            else -> null
        }

        /** Las anotaciones que sirven como tarjeta de repaso (resaltados, subrayados y notas). */
        fun isStudyItem(a: Annotation) = a is TextAnnotation || (a is InkAnnotation && a.kind != InkKind.PEN)
    }
}
