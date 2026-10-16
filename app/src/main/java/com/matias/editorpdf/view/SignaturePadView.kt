package com.matias.editorpdf.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors

/** Recuadro para dibujar la firma con el S Pen (o el dedo). Guarda los trazos en coordenadas de la vista. */
class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Trazos: cada uno es una lista plana x, y, x, y… */
    val strokes = ArrayList<FloatArray>()
    private var current = ArrayList<Float>()
    private val density = resources.displayMetrics.density

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF1A237E.toInt()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * density
        color = MaterialColors.getColor(this@SignaturePadView, com.google.android.material.R.attr.colorOutlineVariant, 0xFFCCCCCC.toInt())
    }
    private val path = Path()

    var onChanged: (() -> Unit)? = null

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun clear() {
        strokes.clear()
        current.clear()
        invalidate()
        onChanged?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        val baseline = height * 0.72f
        canvas.drawLine(width * 0.08f, baseline, width * 0.92f, baseline, linePaint)
        for (s in strokes) drawStroke(canvas, s)
        if (current.size >= 2) drawStroke(canvas, current.toFloatArray())
    }

    private fun drawStroke(canvas: Canvas, s: FloatArray) {
        path.reset()
        path.moveTo(s[0], s[1])
        if (s.size == 2) path.lineTo(s[0] + 0.5f, s[1])
        var i = 2
        while (i + 1 < s.size) {
            path.lineTo(s[i], s[i + 1])
            i += 2
        }
        canvas.drawPath(path, inkPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = ArrayList()
                current.add(e.x); current.add(e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    current.add(e.getHistoricalX(h)); current.add(e.getHistoricalY(h))
                }
                current.add(e.x); current.add(e.y)
            }
            MotionEvent.ACTION_UP -> {
                if (current.size >= 2) strokes.add(current.toFloatArray())
                current = ArrayList()
                onChanged?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> current = ArrayList()
        }
        invalidate()
        return true
    }
}
