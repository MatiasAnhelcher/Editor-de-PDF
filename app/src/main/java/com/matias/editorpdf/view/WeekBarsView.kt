package com.matias.editorpdf.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.animation.ValueAnimator
import com.google.android.material.color.MaterialColors
import kotlin.math.max

/** Gráfico chico de barras con los minutos de estudio de los últimos 7 días. */
class WeekBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var values = FloatArray(7)
    private var labels = List(7) { "" }
    private var goal = 30f
    private var progress = 1f
    private val density = resources.displayMetrics.density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@WeekBarsView, com.google.android.material.R.attr.colorSecondary, 0xFF8A6A2F.toInt())
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@WeekBarsView, com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFFE3DED4.toInt())
    }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@WeekBarsView, com.google.android.material.R.attr.colorPrimary, 0xFF23345C.toInt())
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@WeekBarsView, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF4A4740.toInt())
        textSize = 11 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setData(minutes: List<Int>, dayLabels: List<String>, goalMinutes: Int, animate: Boolean) {
        values = FloatArray(7) { minutes.getOrNull(it)?.toFloat() ?: 0f }
        labels = dayLabels
        goal = goalMinutes.toFloat()
        if (animate) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            progress = 1f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize((96 * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val labelH = 18 * density
        val chartH = height - labelH - paddingTop
        val slot = (width - paddingLeft - paddingRight) / 7f
        val barW = minOf(slot * 0.5f, 18 * density)
        val top = max(goal, values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val r = barW / 2f
        for (i in 0 until 7) {
            val cx = paddingLeft + slot * i + slot / 2f
            val bottom = paddingTop + chartH
            rect.set(cx - barW / 2f, paddingTop.toFloat(), cx + barW / 2f, bottom)
            canvas.drawRoundRect(rect, r, r, emptyPaint)
            val v = values[i] / top * progress
            if (v > 0f) {
                rect.top = bottom - max(chartH * v, barW)
                canvas.drawRoundRect(rect, r, r, if (i == 6) todayPaint else barPaint)
            }
            canvas.drawText(labels.getOrElse(i) { "" }, cx, height - 4 * density, textPaint)
        }
    }
}
