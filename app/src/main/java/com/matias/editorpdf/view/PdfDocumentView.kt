package com.matias.editorpdf.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.util.SizeF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.matias.editorpdf.Settings
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.AnnotationStore
import com.matias.editorpdf.model.InkAnnotation
import com.matias.editorpdf.model.InkKind
import com.matias.editorpdf.model.TextAnnotation
import com.matias.editorpdf.model.Tool
import com.matias.editorpdf.pdf.PageRenderer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Visor del PDF con desplazamiento continuo, zoom y anotaciones.
 * El S Pen escribe con la herramienta elegida; el dedo desplaza y hace zoom
 * (salvo que se active "dibujar también con el dedo").
 */
class PdfDocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onPageChanged(page: Int, pageCount: Int)
        /**
         * Pide escribir texto. Con [boxWidth] > 0 el cuadro ya fue dibujado: (x, y) es su esquina
         * superior izquierda. Si no, (x, y) es el punto tocado.
         */
        fun onTextRequested(page: Int, x: Float, y: Float, existing: TextAnnotation?, boxWidth: Float = 0f)
        fun onSelectionChanged(count: Int) {}

        /** Se llama al terminar un resaltado o subrayado recto, para poder ajustarlo al texto. */
        fun onStraightMarkAdded(a: InkAnnotation) {}
    }

    private class Hit(val page: Int, val x: Float, val y: Float)
    private class Detail(val bitmap: Bitmap, val region: RectF, val scale: Float)
    private enum class Mode { NONE, NAV, DRAW, ERASE, TEXT_PRESS, MOVE_TEXT, DRAW_TEXT_BOX, LASSO, MOVE_SELECTION, IGNORE }

    var listener: Listener? = null
    lateinit var settings: Settings

    var store: AnnotationStore? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Cuadro de texto elegido: solo ese muestra marco y manijas. */
    private var selectedTextId: String? = null

    /** Elige un cuadro de texto (por ejemplo, el recién creado) para que muestre sus manijas. */
    fun selectText(id: String?) {
        selectedTextId = id
        invalidate()
    }

    private fun selectedText(): TextAnnotation? =
        selectedTextId?.let { id -> store?.find(id) as? TextAnnotation }

    var tool: Tool = Tool.PEN
        set(value) {
            cancelToolGesture()
            if (value != Tool.TEXT && value != Tool.HAND) selectedTextId = null
            if (value != Tool.LASSO) clearSelection()
            field = value
            invalidate()
        }

    val currentPage: Int get() = lastReportedPage.coerceAtLeast(0)

    /** Modo repaso: tapa resaltados, subrayados y notas; un toque los destapa. */
    var reviewMode = false
        set(value) {
            cancelToolGesture()
            field = value
            revealed.clear()
            invalidate()
        }
    private val revealed = HashSet<String>()

    private var searchPage = -1
    private var searchRects: List<RectF> = emptyList()

    // --- Documento y geometría -------------------------------------------------------------
    private var renderer: PageRenderer? = null
    private var sizes: List<SizeF> = emptyList()
    private var pageTops = FloatArray(0)
    private var pageLefts = FloatArray(0)
    private var docWidth = 0f
    private var docHeight = 0f
    private var pendingInitialPage = -1

    private var baseScale = 1f
    private var zoom = 1f
    private val scale: Float get() = baseScale * zoom
    private var offX = 0f
    private var offY = 0f

    private val density = resources.displayMetrics.density
    private val margin = 12f * density
    private val gapPts = 12f
    private val eraserRadius = 14f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // --- Imágenes de las páginas ----------------------------------------------------------
    private val baseCache = object : LruCache<Int, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(256L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }
    private val pendingBase = HashSet<Int>()
    private val details = HashMap<Int, Detail>()
    private var baseRenderScale = 1f
    private var layoutToken = 0
    private val detailGen = AtomicInteger()

    @Volatile
    private var visFirst = 0

    @Volatile
    private var visLast = -1
    private var lastReportedPage = -1
    private val detailRunnable = Runnable { renderDetails() }

    // --- Pintura ---------------------------------------------------------------------------
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val readingPaint = Paint()

    /**
     * Modo de lectura. Sepia y noche tiñen la hoja entera (con lo escrito encima), como si se
     * imprimiera en otro papel, para cansar menos la vista.
     */
    var readingMode: com.matias.editorpdf.ReadingMode = com.matias.editorpdf.ReadingMode.NORMAL
        set(value) {
            field = value
            readingPaint.colorFilter = when (value) {
                com.matias.editorpdf.ReadingMode.NORMAL -> null
                // Blanco → papel crema, negro → marrón muy oscuro.
                com.matias.editorpdf.ReadingMode.SEPIA -> android.graphics.ColorMatrixColorFilter(
                    floatArrayOf(
                        0.82f, 0f, 0f, 0f, 40f,
                        0f, 0.82f, 0f, 0f, 30f,
                        0f, 0f, 0.78f, 0f, 20f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
                // Invierte con suavidad: el blanco pasa a gris casi negro y el negro a gris claro.
                com.matias.editorpdf.ReadingMode.NIGHT -> android.graphics.ColorMatrixColorFilter(
                    floatArrayOf(
                        -0.85f, 0f, 0f, 0f, 232f,
                        0f, -0.85f, 0f, 0f, 232f,
                        0f, 0f, -0.85f, 0f, 228f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            }
            invalidate()
        }
    private val shadowPaint = Paint().apply { color = 0x1A000000 }
    private val backgroundColor = context.getColor(com.matias.editorpdf.R.color.c_canvas)
    private val painter = AnnotationPainter()
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF5B4BC4.toInt()
    }
    private val textFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = TEXT_ACCENT
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val searchPaint = Paint().apply { color = 0x66FF9800 }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xAA555555.toInt()
    }
    private val tmpRect = RectF()

    // --- Gestos ----------------------------------------------------------------------------
    private var mode = Mode.NONE
    private var activeId = -1
    private var activeIsPen = false
    private var navFromDown = false
    private var navMoved = false
    private var navMaxPointers = 0
    private var navDownX = 0f
    private var navDownY = 0f
    private var lastFx = 0f
    private var lastFy = 0f
    private var velocityTracker: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var zoomAnimator: ValueAnimator? = null

    private var strokePage = -1
    private var strokeKind = InkKind.PEN
    private var strokeStraight = false
    private var strokeColor = Color.BLACK
    private var strokeWidth = 1.5f
    private var strokePts = FloatArray(3 * 256)
    private var strokeCount = 0

    private val erased = ArrayList<Annotation>()
    private var eraserX = 0f
    private var eraserY = 0f

    private var downX = 0f
    private var downY = 0f
    private var pressedText: TextAnnotation? = null
    private var movingText: TextAnnotation? = null
    private var resizingText = false
    private var resizeEdge = 1
    private var textBoxPage = -1
    private val textBox = RectF()

    // Lazo: selección de anotaciones para mover, recolorear o borrar.
    private var lassoPage = -1
    private val lassoPath = Path()
    private val lassoPoly = ArrayList<Float>()
    private val selected = LinkedHashSet<String>()
    private var selectionPage = -1
    private var selDx = 0f
    private var selDy = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val doubleTap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoomTo(if (zoom < 1.6f) 2.5f else 1f, e.x, e.y)
            return true
        }
    })

    // ======================================================================================
    // Documento
    // ======================================================================================

    fun setDocument(renderer: PageRenderer, pageSizes: List<SizeF>, initialPage: Int) {
        this.renderer = renderer
        sizes = pageSizes
        pendingInitialPage = initialPage.coerceIn(0, max(0, pageSizes.size - 1))
        baseCache.evictAll()
        details.clear()
        pendingBase.clear()
        layoutToken++
        relayout(keepAnchor = false)
    }

    fun scrollToPage(page: Int) {
        if (sizes.isEmpty() || width == 0) {
            pendingInitialPage = page
            return
        }
        val p = page.coerceIn(0, sizes.size - 1)
        scroller.forceFinished(true)
        offY = margin - pageTops[p] * scale
        clampOffsets()
        invalidate()
        scheduleDetails()
    }

    /** Destapa (o vuelve a tapar) todo en el modo repaso. */
    fun revealAll(show: Boolean) {
        revealed.clear()
        if (show) store?.all()?.forEach { revealed.add(it.id) }
        invalidate()
    }

    fun showSearchMatch(page: Int, rects: List<RectF>) {
        searchPage = page
        searchRects = rects
        if (sizes.isEmpty() || width == 0) return
        val p = page.coerceIn(0, sizes.size - 1)
        val top = rects.minOfOrNull { it.top } ?: 0f
        scroller.forceFinished(true)
        offY = height / 3f - (pageTops[p] + top) * scale
        clampOffsets()
        invalidate()
        scheduleDetails()
    }

    fun clearSearchMatch() {
        searchPage = -1
        searchRects = emptyList()
        invalidate()
    }

    /** Lleva a la vista la zona [y] (en puntos) de la página. */
    fun scrollToPagePoint(page: Int, y: Float) {
        if (sizes.isEmpty() || width == 0) return
        val p = page.coerceIn(0, sizes.size - 1)
        scroller.forceFinished(true)
        offY = height / 3f - (pageTops[p] + y) * scale
        clampOffsets()
        invalidate()
        scheduleDetails()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout(keepAnchor = oldw > 0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(detailRunnable)
        zoomAnimator?.cancel()
    }

    private fun relayout(keepAnchor: Boolean) {
        if (sizes.isEmpty() || width == 0 || height == 0) return
        val anchorX = (width / 2f - offX) / scale
        val anchorY = (height / 2f - offY) / scale

        docWidth = sizes.maxOf { it.width }
        pageTops = FloatArray(sizes.size)
        pageLefts = FloatArray(sizes.size)
        var y = 0f
        sizes.forEachIndexed { i, s ->
            pageTops[i] = y
            pageLefts[i] = (docWidth - s.width) / 2f
            y += s.height + gapPts
        }
        docHeight = y - gapPts
        baseScale = (width - 2 * margin) / docWidth

        val newBase = min(baseScale, MAX_BASE_PX / docWidth)
        if (newBase != baseRenderScale) {
            baseRenderScale = newBase
            baseCache.evictAll()
            pendingBase.clear()
            layoutToken++
        }
        details.clear()

        if (pendingInitialPage >= 0) {
            val p = pendingInitialPage
            pendingInitialPage = -1
            offX = margin
            offY = margin - pageTops[p] * scale
        } else if (keepAnchor) {
            offX = width / 2f - anchorX * scale
            offY = height / 2f - anchorY * scale
        } else {
            offX = margin
            offY = margin
        }
        clampOffsets()
        invalidate()
        scheduleDetails()
    }

    private fun xBounds(): Pair<Float, Float> {
        val cw = docWidth * scale
        return if (cw + 2 * margin <= width) {
            val c = (width - cw) / 2f
            c to c
        } else {
            (width - margin - cw) to margin
        }
    }

    private fun yBounds(): Pair<Float, Float> {
        val ch = docHeight * scale
        return if (ch + 2 * margin <= height) margin to margin else (height - margin - ch) to margin
    }

    private fun clampOffsets() {
        val (minX, maxX) = xBounds()
        val (minY, maxY) = yBounds()
        offX = offX.coerceIn(minX, maxX)
        offY = offY.coerceIn(minY, maxY)
    }

    private fun pageScreenRect(i: Int, out: RectF = RectF()): RectF {
        val s = scale
        val l = offX + pageLefts[i] * s
        val t = offY + pageTops[i] * s
        out.set(l, t, l + sizes[i].width * s, t + sizes[i].height * s)
        return out
    }

    private fun updateVisibleRange() {
        if (sizes.isEmpty()) return
        val s = scale
        val top = -offY / s
        val bottom = (height - offY) / s
        var first = pageTops.binarySearchFloor(top)
        if (first < 0) first = 0
        var last = first
        while (last + 1 < sizes.size && pageTops[last + 1] <= bottom) last++
        visFirst = first
        visLast = last

        val center = (height / 2f - offY) / s
        var current = pageTops.binarySearchFloor(center).coerceIn(0, sizes.size - 1)
        if (current < 0) current = 0
        if (current != lastReportedPage) {
            lastReportedPage = current
            listener?.onPageChanged(current, sizes.size)
        }
    }

    private fun FloatArray.binarySearchFloor(v: Float): Int {
        var lo = 0
        var hi = size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid] <= v) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun screenToPage(sx: Float, sy: Float): Hit? {
        if (sizes.isEmpty()) return null
        val s = scale
        for (i in max(0, visFirst - 1)..min(sizes.size - 1, visLast + 1)) {
            val r = pageScreenRect(i, tmpRect)
            if (sx >= r.left && sx <= r.right && sy >= r.top && sy <= r.bottom) {
                return Hit(i, (sx - r.left) / s, (sy - r.top) / s)
            }
        }
        return null
    }

    // ======================================================================================
    // Dibujo de páginas
    // ======================================================================================

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(backgroundColor)
        if (sizes.isEmpty()) return
        updateVisibleRange()
        val s = scale
        val shadow = 2f * density
        val pr = RectF()
        for (i in visFirst..visLast) {
            pageScreenRect(i, pr)
            canvas.drawRect(pr.left + shadow, pr.top + shadow, pr.right + shadow, pr.bottom + shadow, shadowPaint)
            val layer = if (readingPaint.colorFilter != null) canvas.saveLayer(pr, readingPaint) else -1
            canvas.drawRect(pr, pagePaint)
            val base = baseCache.get(i)
            if (base != null) canvas.drawBitmap(base, null, pr, bitmapPaint) else requestBase(i)
            details[i]?.let { d ->
                tmpRect.set(
                    pr.left + d.region.left * s, pr.top + d.region.top * s,
                    pr.left + d.region.right * s, pr.top + d.region.bottom * s,
                )
                canvas.drawBitmap(d.bitmap, null, tmpRect, bitmapPaint)
            }
            drawPageAnnotations(canvas, i, pr, s)
            if (layer >= 0) canvas.restoreToCount(layer)
        }
        if (visFirst > 0 && baseCache.get(visFirst - 1) == null) requestBase(visFirst - 1)
        if (visLast + 1 < sizes.size && baseCache.get(visLast + 1) == null) requestBase(visLast + 1)

        if (mode == Mode.ERASE) canvas.drawCircle(eraserX, eraserY, eraserRadius, eraserPaint)
    }

    private fun requestBase(page: Int) {
        val r = renderer ?: return
        if (!pendingBase.add(page)) return
        val sz = sizes[page]
        val token = layoutToken
        r.render(page, RectF(0f, 0f, sz.width, sz.height), baseRenderScale,
            isNeeded = { page in (visFirst - 2)..(visLast + 2) }) { bmp ->
            if (token != layoutToken) return@render
            pendingBase.remove(page)
            if (bmp != null) {
                baseCache.put(page, bmp)
                invalidate()
            }
        }
    }

    private fun scheduleDetails() {
        removeCallbacks(detailRunnable)
        postDelayed(detailRunnable, 140)
    }

    /** Cuando hay zoom, vuelve a dibujar en alta resolución solo la parte visible de cada página. */
    private fun renderDetails() {
        val r = renderer ?: return
        if (sizes.isEmpty()) return
        updateVisibleRange()
        val gen = detailGen.incrementAndGet()
        val s = scale
        val keep = HashSet<Int>()
        val pr = RectF()
        for (i in visFirst..visLast) {
            if (baseRenderScale >= s * 0.95f) continue
            pageScreenRect(i, pr)
            val vis = RectF(pr)
            if (!vis.intersect(0f, 0f, width.toFloat(), height.toFloat())) continue
            val region = RectF(
                (vis.left - pr.left) / s, (vis.top - pr.top) / s,
                (vis.right - pr.left) / s, (vis.bottom - pr.top) / s,
            )
            keep.add(i)
            val existing = details[i]
            if (existing != null && existing.scale == s && existing.region.contains(region)) continue
            r.render(i, region, s, isNeeded = { detailGen.get() == gen }) { bmp ->
                if (bmp != null && detailGen.get() == gen) {
                    details[i] = Detail(bmp, region, s)
                    invalidate()
                }
            }
        }
        details.keys.retainAll(keep)
    }

    // ======================================================================================
    // Dibujo de anotaciones
    // ======================================================================================

    private fun drawPageAnnotations(canvas: Canvas, page: Int, pr: RectF, s: Float) {
        val list = store?.forPage(page).orEmpty()
        val drawingHere = mode == Mode.DRAW && strokePage == page
        val lassoHere = mode == Mode.LASSO && lassoPage == page
        val selectionHere = selectionPage == page && selected.isNotEmpty()
        val moveSel = mode == Mode.MOVE_SELECTION && selectionHere
        val moving = movingText?.takeIf { it.page == page }
        val searchHere = searchPage == page && searchRects.isNotEmpty()
        val boxHere = mode == Mode.DRAW_TEXT_BOX && textBoxPage == page
        if (list.isEmpty() && !drawingHere && moving == null && !searchHere && !lassoHere && !boxHere) return
        canvas.save()
        canvas.clipRect(pr)
        canvas.translate(pr.left, pr.top)
        canvas.scale(s, s)
        for (a in list) {
            if (moving != null && a.id == moving.id) continue
            if (moveSel && a.id in selected) {
                painter.draw(canvas, a.moveBy(selDx, selDy), cache = false)
                continue
            }
            if (reviewMode && a.id !in revealed && painter.drawCover(canvas, a)) continue
            painter.draw(canvas, a, cache = true)
        }
        if (searchHere) searchRects.forEach { canvas.drawRect(it, searchPaint) }
        if (moving != null) painter.draw(canvas, moving, cache = false)
        if ((tool == Tool.TEXT || tool == Tool.HAND) && !reviewMode) {
            // Solo el cuadro elegido (o el que se está moviendo) muestra marco y manijas.
            val framed = moving ?: selectedText()?.takeIf { it.page == page }
            if (framed != null) drawTextFrame(canvas, framed, s)
        }
        if (boxHere) {
            selectionPaint.strokeWidth = 1.5f * density / s
            selectionPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density / s, 4f * density / s), 0f)
            canvas.drawRect(textBox, selectionPaint)
        }
        if (drawingHere) currentStroke()?.let { painter.draw(canvas, it, cache = false) }
        if (lassoHere || selectionHere) {
            selectionPaint.strokeWidth = 1.5f * density / s
            selectionPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density / s, 4f * density / s), 0f)
            if (lassoHere) canvas.drawPath(lassoPath, selectionPaint)
            if (selectionHere) selectionBounds()?.let { b ->
                b.offset(if (moveSel) selDx else 0f, if (moveSel) selDy else 0f)
                canvas.drawRect(b, selectionPaint)
            }
        }
        canvas.restore()
    }

    private fun textFrame(t: TextAnnotation): RectF = RectF(t.bounds).apply { inset(-FRAME_PAD, -FRAME_PAD) }

    private fun drawTextFrame(canvas: Canvas, t: TextAnnotation, s: Float) {
        val b = textFrame(t)
        val corner = 3f * density / s
        textFramePaint.strokeWidth = 1.5f * density / s
        canvas.drawRoundRect(b, corner, corner, textFramePaint)
        // Manijas (las "orejas") en el medio de cada costado: una pastilla vertical para cambiar el ancho.
        val hw = 4f * density / s
        val hh = min(11f * density / s, b.height() / 2f + hw)
        for (hx in floatArrayOf(b.left, b.right)) {
            val r = RectF(hx - hw, b.centerY() - hh, hx + hw, b.centerY() + hh)
            handlePaint.style = Paint.Style.FILL
            handlePaint.color = Color.WHITE
            canvas.drawRoundRect(r, hw, hw, handlePaint)
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = 1.5f * density / s
            handlePaint.color = TEXT_ACCENT
            canvas.drawRoundRect(r, hw, hw, handlePaint)
        }
    }

    /** Como [screenToPage] pero sin exigir caer dentro de la hoja (para manijas que quedan en el borde). */
    private fun screenToPageLoose(sx: Float, sy: Float): Hit? {
        screenToPage(sx, sy)?.let { return it }
        if (sizes.isEmpty()) return null
        val s = scale
        val slack = 24f * density
        for (i in max(0, visFirst - 1)..min(sizes.size - 1, visLast + 1)) {
            val r = pageScreenRect(i, tmpRect)
            if (sx >= r.left - slack && sx <= r.right + slack && sy >= r.top - slack && sy <= r.bottom + slack) {
                return Hit(i, (sx - r.left) / s, (sy - r.top) / s)
            }
        }
        return null
    }

    private class TextGrab(val text: TextAnnotation, val edge: Int)

    /** Qué cuadro de texto hay bajo el dedo: edge -1/1 = manija izquierda/derecha, 0 = el cuadro. */
    private fun textGrabAt(x: Float, y: Float): TextGrab? {
        val h = screenToPageLoose(x, y) ?: return null
        val texts = store?.forPage(h.page)?.filterIsInstance<TextAnnotation>().orEmpty()
        if (texts.isEmpty()) return null
        // Las manijas solo existen en el cuadro elegido.
        texts.firstOrNull { it.id == selectedTextId }?.let { t ->
            val grab = 18f * density / scale
            val b = textFrame(t)
            if (h.y >= b.top - grab && h.y <= b.bottom + grab) {
                if (abs(h.x - b.right) <= grab) return TextGrab(t, 1)
                if (abs(h.x - b.left) <= grab) return TextGrab(t, -1)
            }
        }
        val pad = 8f * density / scale
        return texts.lastOrNull { it.hitTest(h.x, h.y, pad) }?.let { TextGrab(it, 0) }
    }

    private fun onReviewTap(x: Float, y: Float) {
        val hit = screenToPage(x, y) ?: return
        val pad = 8f * density / scale
        val a = store?.forPage(hit.page)?.lastOrNull { a ->
            AnnotationPainter.coverRect(a)?.let { r -> hit.x >= r.left - pad && hit.x <= r.right + pad && hit.y >= r.top - pad && hit.y <= r.bottom + pad } == true
        } ?: return
        if (!revealed.add(a.id)) revealed.remove(a.id)
        invalidate()
    }

    // ======================================================================================
    // Entrada táctil y S Pen
    // ======================================================================================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (sizes.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(e)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(e)
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_CANCEL -> onCancel(e)
        }
        return true
    }

    private fun isPen(e: MotionEvent, idx: Int): Boolean {
        val t = e.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER || t == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun onDown(e: MotionEvent) {
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        mode = Mode.NONE
        val pen = isPen(e, 0)
        val fingerMayDraw = !settings.stylusOnly || tool == Tool.TEXT
        if (!reviewMode && tool == Tool.HAND && textGrabAt(e.getX(0), e.getY(0)) != null) {
            // Con la herramienta Mover, tocar un cuadro de texto lo agarra (para moverlo o ensancharlo).
            beginToolGesture(e, 0, pen)
        } else if (tool == Tool.HAND && selectedTextId != null) {
            // Tocar fuera del cuadro elegido lo suelta y esconde sus manijas.
            selectText(null)
            startNav(e, fromDown = true)
        } else if (reviewMode || tool == Tool.HAND || (!pen && !fingerMayDraw)) {
            startNav(e, fromDown = true)
        } else {
            beginToolGesture(e, 0, pen)
        }
    }

    private fun onPointerDown(e: MotionEvent) {
        val idx = e.actionIndex
        val pen = isPen(e, idx)
        when (mode) {
            Mode.NAV -> {
                if (pen && tool != Tool.HAND && !reviewMode) {
                    // La palma estaba apoyada y llegó el lápiz: el lápiz manda.
                    endNav()
                    beginToolGesture(e, idx, true)
                } else {
                    handleNav(e)
                }
            }
            Mode.NONE -> if (pen && tool != Tool.HAND && !reviewMode) beginToolGesture(e, idx, true) else startNav(e, fromDown = false)
            else -> if (!activeIsPen && !pen) {
                // Segundo dedo mientras se dibujaba con el dedo: pasa a ser zoom/desplazamiento.
                cancelToolGesture()
                startNav(e, fromDown = false)
            }
            // Si el lápiz está activo, los dedos (la palma) se ignoran.
        }
    }

    private fun onMove(e: MotionEvent) {
        when (mode) {
            Mode.NAV -> handleNav(e)
            Mode.DRAW -> moveStroke(e)
            Mode.ERASE -> moveErase(e)
            Mode.TEXT_PRESS, Mode.MOVE_TEXT, Mode.DRAW_TEXT_BOX -> moveText(e)
            Mode.LASSO, Mode.MOVE_SELECTION -> moveLasso(e)
            else -> Unit
        }
    }

    private fun onPointerUp(e: MotionEvent) {
        if (mode == Mode.NAV) {
            handleNav(e)
        } else if (e.getPointerId(e.actionIndex) == activeId) {
            finishToolGesture(e)
        }
    }

    private fun onUp(e: MotionEvent) {
        if (mode == Mode.NAV) {
            handleNav(e)
        } else if (e.getPointerId(e.actionIndex) == activeId) {
            finishToolGesture(e)
        }
        mode = Mode.NONE
        activeId = -1
    }

    private fun onCancel(@Suppress("UNUSED_PARAMETER") e: MotionEvent) {
        if (mode == Mode.NAV) endNav() else cancelToolGesture()
        mode = Mode.NONE
        activeId = -1
    }

    private fun beginToolGesture(e: MotionEvent, idx: Int, pen: Boolean) {
        activeId = e.getPointerId(idx)
        activeIsPen = pen
        if (pen) requestUnbufferedDispatch(e)
        val sideButton = e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER ||
            (pen && (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
        val x = e.getX(idx)
        val y = e.getY(idx)
        when {
            sideButton || tool == Tool.ERASER -> startErase(x, y)
            tool == Tool.TEXT || tool == Tool.HAND -> startTextPress(x, y)
            tool == Tool.LASSO -> startLasso(x, y)
            else -> startStroke(x, y, pressure(e, idx, -1))
        }
    }

    private fun finishToolGesture(e: MotionEvent) {
        when (mode) {
            Mode.DRAW -> {
                val idx = e.findPointerIndex(activeId)
                if (idx >= 0) addStrokePoint(e.getX(idx), e.getY(idx), pressure(e, idx, -1))
                finishStroke()
            }
            Mode.ERASE -> finishErase()
            Mode.TEXT_PRESS -> {
                val t = pressedText
                val hit = screenToPage(downX, downY)
                when {
                    // Como en Acrobat: el primer toque elige el cuadro, el segundo abre el texto.
                    t != null && t.id == selectedTextId -> listener?.onTextRequested(t.page, t.x, t.y, t)
                    t != null -> selectText(t.id)
                    // Tocar afuera suelta el cuadro elegido; recién el siguiente toque crea uno nuevo.
                    selectedTextId != null -> selectText(null)
                    hit != null && tool == Tool.TEXT -> listener?.onTextRequested(hit.page, hit.x, hit.y, null)
                }
            }
            Mode.LASSO -> finishLasso()
            Mode.MOVE_SELECTION -> finishMoveSelection()
            Mode.MOVE_TEXT -> {
                val from = pressedText
                val to = movingText
                if (from != null && to != null) store?.commit(AnnotationStore.Change(listOf(from), listOf(to)))
                if (to != null) selectedTextId = to.id
            }
            Mode.DRAW_TEXT_BOX -> {
                selectedTextId = null
                if (textBox.width() >= MIN_BOX_WIDTH) {
                    listener?.onTextRequested(textBoxPage, textBox.left, textBox.top, null, textBox.width())
                }
                textBoxPage = -1
            }
            else -> Unit
        }
        pressedText = null
        movingText = null
        resizingText = false
        mode = Mode.NONE
        activeId = -1
        invalidate()
    }

    private fun cancelToolGesture() {
        if (mode == Mode.LASSO) lassoPath.reset()
        if (mode == Mode.MOVE_SELECTION) {
            selDx = 0f
            selDy = 0f
        }
        when (mode) {
            Mode.ERASE -> finishErase()
            Mode.DRAW -> strokeCount = 0
            else -> Unit
        }
        pressedText = null
        movingText = null
        resizingText = false
        textBoxPage = -1
        if (mode != Mode.NAV) mode = Mode.NONE
        invalidate()
    }

    private fun pressure(e: MotionEvent, idx: Int, h: Int): Float {
        if (!activeIsPen) return 0.55f
        return if (h >= 0) e.getHistoricalPressure(idx, h) else e.getPressure(idx)
    }

    // --- Navegación (desplazar / zoom) ---------------------------------------------------

    private fun startNav(e: MotionEvent, fromDown: Boolean) {
        mode = Mode.NAV
        navFromDown = fromDown
        navMoved = !fromDown
        navMaxPointers = e.pointerCount
        navDownX = e.getX(0)
        navDownY = e.getY(0)
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain()
        val (fx, fy) = focus(e, -1)
        lastFx = fx
        lastFy = fy
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            handleNav(e)
        } else {
            velocityTracker?.addMovement(e)
        }
    }

    private fun endNav() {
        velocityTracker?.recycle()
        velocityTracker = null
        mode = Mode.NONE
    }

    private fun focus(e: MotionEvent, skip: Int): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            sx += e.getX(i)
            sy += e.getY(i)
            n++
        }
        if (n == 0) return e.x to e.y
        return sx / n to sy / n
    }

    private fun handleNav(e: MotionEvent) {
        scaleDetector.onTouchEvent(e)
        if (navFromDown && tool != Tool.TEXT && !reviewMode) doubleTap.onTouchEvent(e)
        navMaxPointers = max(navMaxPointers, e.pointerCount)
        if (!navMoved && hypot(e.getX(0) - navDownX, e.getY(0) - navDownY) > touchSlop) navMoved = true
        velocityTracker?.addMovement(e)
        val action = e.actionMasked
        val (fx, fy) = focus(e, if (action == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1)
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                panBy(fx - lastFx, fy - lastFy)
                lastFx = fx
                lastFy = fy
            }
            MotionEvent.ACTION_UP -> {
                if (reviewMode && !navMoved && navMaxPointers == 1) onReviewTap(e.x, e.y)
                velocityTracker?.let { vt ->
                    vt.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                    val id = e.getPointerId(e.actionIndex)
                    fling(vt.getXVelocity(id), vt.getYVelocity(id))
                }
                endNav()
            }
            else -> {
                lastFx = fx
                lastFy = fy
            }
        }
    }

    private fun panBy(dx: Float, dy: Float) {
        offX += dx
        offY += dy
        clampOffsets()
        invalidate()
        scheduleDetails()
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = newZoom / zoom
        offX = fx - (fx - offX) * f
        offY = fy - (fy - offY) * f
        zoom = newZoom
        clampOffsets()
        invalidate()
        scheduleDetails()
    }

    private fun animateZoomTo(target: Float, fx: Float, fy: Float) {
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 220
            addUpdateListener { zoomBy((it.animatedValue as Float) / zoom, fx, fy) }
            start()
        }
    }

    private fun fling(vx: Float, vy: Float) {
        if (abs(vx) < 50 && abs(vy) < 50) return
        val (minX, maxX) = xBounds()
        val (minY, maxY) = yBounds()
        scroller.fling(
            offX.roundToInt(), offY.roundToInt(), vx.roundToInt(), vy.roundToInt(),
            minX.roundToInt(), maxX.roundToInt(), minY.roundToInt(), maxY.roundToInt(),
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.currX.toFloat()
            offY = scroller.currY.toFloat()
            clampOffsets()
            postInvalidateOnAnimation()
            scheduleDetails()
        }
    }

    // --- Trazos (lápiz, resaltador, subrayado) ----------------------------------------------

    private fun startStroke(x: Float, y: Float, p: Float) {
        val hit = screenToPage(x, y)
        if (hit == null) {
            mode = Mode.IGNORE
            return
        }
        strokePage = hit.page
        strokeKind = when (tool) {
            Tool.HIGHLIGHTER -> InkKind.HIGHLIGHTER
            Tool.UNDERLINE -> InkKind.UNDERLINE
            else -> InkKind.PEN
        }
        val styleTool = when (strokeKind) {
            InkKind.HIGHLIGHTER -> Tool.HIGHLIGHTER
            InkKind.UNDERLINE -> Tool.UNDERLINE
            InkKind.PEN -> Tool.PEN
        }
        strokeColor = settings.color(styleTool)
        strokeWidth = settings.width(styleTool)
        strokeStraight = strokeKind == InkKind.UNDERLINE ||
            (strokeKind == InkKind.HIGHLIGHTER && settings.straightHighlighter)
        strokeCount = 0
        appendPoint(hit.x, hit.y, p)
        mode = Mode.DRAW
        invalidate()
    }

    private fun moveStroke(e: MotionEvent) {
        val idx = e.findPointerIndex(activeId)
        if (idx < 0) return
        for (h in 0 until e.historySize) {
            addStrokePoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), pressure(e, idx, h))
        }
        addStrokePoint(e.getX(idx), e.getY(idx), pressure(e, idx, -1))
        invalidate()
    }

    private fun addStrokePoint(sx: Float, sy: Float, p: Float) {
        if (strokePage < 0 || strokeCount == 0) return
        val s = scale
        val sz = sizes[strokePage]
        val px = ((sx - offX) / s - pageLefts[strokePage]).coerceIn(0f, sz.width)
        val py = ((sy - offY) / s - pageTops[strokePage]).coerceIn(0f, sz.height)
        if (strokeStraight) {
            if (strokeCount >= 2) strokeCount = 1
            appendPoint(px, py, p)
            return
        }
        val lx = strokePts[(strokeCount - 1) * 3]
        val ly = strokePts[(strokeCount - 1) * 3 + 1]
        if (hypot(px - lx, py - ly) * s < 1.2f) return
        appendPoint(px, py, p)
    }

    private fun appendPoint(x: Float, y: Float, p: Float) {
        if ((strokeCount + 1) * 3 > strokePts.size) strokePts = strokePts.copyOf(strokePts.size * 2)
        strokePts[strokeCount * 3] = x
        strokePts[strokeCount * 3 + 1] = y
        strokePts[strokeCount * 3 + 2] = p
        strokeCount++
    }

    /** El trazo en curso, con el "imán" horizontal aplicado a las líneas rectas. */
    private fun currentStroke(): InkAnnotation? {
        if (strokeCount == 0) return null
        val pts = strokePts.copyOf(strokeCount * 3)
        if (strokeStraight && strokeCount >= 2) {
            val dx = pts[3] - pts[0]
            val dy = pts[4] - pts[1]
            if (abs(dy) < abs(dx) * 0.14f) pts[4] = pts[1]
        }
        return InkAnnotation("current", strokePage, strokeKind, strokeColor, strokeWidth, pts, strokeStraight)
    }

    private fun finishStroke() {
        val current = currentStroke()
        strokeCount = 0
        mode = Mode.NONE
        if (current == null) return
        if (current.straight) {
            if (current.pointCount < 2) return
            val len = hypot(current.x(1) - current.x(0), current.y(1) - current.y(0))
            if (len < 2f) return
        }
        val a = InkAnnotation(
            Annotation.newId(), current.page, current.kind, current.color, current.width,
            current.points, current.straight,
        )
        store?.commit(AnnotationStore.Change(emptyList(), listOf(a)))
        if (a.straight) listener?.onStraightMarkAdded(a)
    }

    // --- Borrador ----------------------------------------------------------------------------

    private fun startErase(x: Float, y: Float) {
        mode = Mode.ERASE
        erased.clear()
        eraserX = x
        eraserY = y
        eraseAt(x, y)
        invalidate()
    }

    private fun moveErase(e: MotionEvent) {
        val idx = e.findPointerIndex(activeId)
        if (idx < 0) return
        for (h in 0 until e.historySize) eraseAlong(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
        eraseAlong(e.getX(idx), e.getY(idx))
        invalidate()
    }

    private fun eraseAlong(x: Float, y: Float) {
        val d = hypot(x - eraserX, y - eraserY)
        val steps = max(1, ceil(d / (eraserRadius * 0.5f)).toInt())
        val sx = eraserX
        val sy = eraserY
        for (k in 1..steps) {
            val t = k.toFloat() / steps
            eraseAt(sx + (x - sx) * t, sy + (y - sy) * t)
        }
        eraserX = x
        eraserY = y
    }

    private fun eraseAt(x: Float, y: Float) {
        val st = store ?: return
        val hit = screenToPage(x, y) ?: return
        val r = eraserRadius / scale
        val hits = st.forPage(hit.page).filter { it.hitTest(hit.x, hit.y, r) }
        if (hits.isNotEmpty()) {
            st.applySilently(hits, emptyList())
            erased.addAll(hits)
        }
    }

    private fun finishErase() {
        if (erased.isNotEmpty()) store?.record(AnnotationStore.Change(erased.toList(), emptyList()))
        erased.clear()
        mode = Mode.NONE
    }

    // --- Texto -----------------------------------------------------------------------------

    private fun startTextPress(x: Float, y: Float) {
        downX = x
        downY = y
        mode = Mode.TEXT_PRESS
        // Primero las manijas de los costados (cambiar ancho), después el cuadro entero (mover/editar).
        val g = textGrabAt(x, y)
        pressedText = g?.text
        resizingText = g != null && g.edge != 0
        resizeEdge = g?.edge ?: 1
    }

    private fun moveText(e: MotionEvent) {
        val idx = e.findPointerIndex(activeId)
        if (idx < 0) return
        val x = e.getX(idx)
        val y = e.getY(idx)
        if (mode == Mode.TEXT_PRESS && hypot(x - downX, y - downY) > touchSlop) {
            when {
                pressedText != null -> mode = Mode.MOVE_TEXT
                activeIsPen && screenToPage(downX, downY) != null -> {
                    // Arrastrar el lápiz sobre un lugar vacío dibuja el cuadro, como en Acrobat.
                    textBoxPage = screenToPage(downX, downY)!!.page
                    mode = Mode.DRAW_TEXT_BOX
                }
                !activeIsPen -> {
                    startNav(e, fromDown = false)
                    return
                }
                else -> {
                    mode = Mode.IGNORE
                    return
                }
            }
        }
        if (mode == Mode.DRAW_TEXT_BOX) {
            val a = screenToPage(downX, downY) ?: return
            val sz = sizes[textBoxPage]
            val bx = (a.x + (x - downX) / scale).coerceIn(0f, sz.width)
            val by = (a.y + (y - downY) / scale).coerceIn(0f, sz.height)
            textBox.set(min(a.x, bx), min(a.y, by), max(a.x, bx), max(a.y, by))
            invalidate()
            return
        }
        if (mode == Mode.MOVE_TEXT && resizingText) {
            val t = pressedText ?: return
            val sz = sizes[t.page]
            val start = if (t.boxWidth > 0f) t.boxWidth else t.bounds.width()
            val minW = max(MIN_BOX_WIDTH, t.size * 2f)
            val d = (x - downX) / scale
            movingText = if (resizeEdge > 0) {
                t.withBoxWidth((start + d).coerceIn(minW, max(minW, sz.width - t.x)))
            } else {
                // Manija izquierda: el borde derecho queda fijo y se corre el izquierdo.
                val right = t.x + start
                val left = (t.x + d).coerceIn(0f, right - minW)
                t.moved(left - t.x, 0f).withBoxWidth(right - left)
            }
            invalidate()
            return
        }
        if (mode == Mode.MOVE_TEXT) {
            val t = pressedText ?: return
            val s = scale
            val sz = sizes[t.page]
            val w = t.bounds.width().coerceAtMost(sz.width)
            val dx = ((x - downX) / s).coerceIn(-t.x, max(-t.x, sz.width - t.x - w))
            val dy = ((y - downY) / s).coerceIn(-t.y, sz.height - t.y - 4f)
            movingText = t.moved(dx, dy)
            invalidate()
        }
    }

    // --- Lazo --------------------------------------------------------------------------------

    val selectionCount: Int get() = selected.size

    fun clearSelection() {
        if (selected.isEmpty() && selectionPage < 0) return
        selected.clear()
        selectionPage = -1
        listener?.onSelectionChanged(0)
        invalidate()
    }

    fun recolorSelection(color: Int) {
        val st = store ?: return
        val old = selected.mapNotNull { st.find(it) }
        if (old.isEmpty()) return
        st.commit(AnnotationStore.Change(old, old.map { it.recolored(color) }))
    }

    fun deleteSelection() {
        val st = store ?: return
        val old = selected.mapNotNull { st.find(it) }
        if (old.isNotEmpty()) st.commit(AnnotationStore.Change(old, emptyList()))
        clearSelection()
    }

    private fun selectionBounds(): RectF? {
        val st = store ?: return null
        var r: RectF? = null
        for (id in selected) {
            val a = st.find(id) ?: continue
            if (r == null) r = RectF(a.bounds) else r.union(a.bounds)
        }
        r?.inset(-4f, -4f)
        return r
    }

    private fun startLasso(x: Float, y: Float) {
        val hit = screenToPage(x, y)
        if (hit == null) {
            mode = Mode.IGNORE
            return
        }
        val b = if (hit.page == selectionPage) selectionBounds() else null
        if (b != null && b.contains(hit.x, hit.y)) {
            mode = Mode.MOVE_SELECTION
            downX = x
            downY = y
            selDx = 0f
            selDy = 0f
            return
        }
        clearSelection()
        mode = Mode.LASSO
        lassoPage = hit.page
        lassoPoly.clear()
        lassoPath.reset()
        lassoPath.moveTo(hit.x, hit.y)
        lassoPoly.add(hit.x)
        lassoPoly.add(hit.y)
        invalidate()
    }

    private fun moveLasso(e: MotionEvent) {
        val idx = e.findPointerIndex(activeId)
        if (idx < 0) return
        val s = scale
        if (mode == Mode.MOVE_SELECTION) {
            selDx = (e.getX(idx) - downX) / s
            selDy = (e.getY(idx) - downY) / s
            invalidate()
            return
        }
        val sz = sizes[lassoPage]
        val px = ((e.getX(idx) - offX) / s - pageLefts[lassoPage]).coerceIn(0f, sz.width)
        val py = ((e.getY(idx) - offY) / s - pageTops[lassoPage]).coerceIn(0f, sz.height)
        lassoPath.lineTo(px, py)
        lassoPoly.add(px)
        lassoPoly.add(py)
        invalidate()
    }

    private fun finishLasso() {
        lassoPath.reset()
        val st = store
        if (st == null || lassoPoly.size < 6) {
            mode = Mode.NONE
            return
        }
        val poly = lassoPoly.toFloatArray()
        for (a in st.forPage(lassoPage)) {
            val inside = when (a) {
                is TextAnnotation -> pointInPolygon(poly, a.bounds.centerX(), a.bounds.centerY())
                is InkAnnotation -> {
                    val idx = a.drawnIndices()
                    idx.count { pointInPolygon(poly, a.x(it), a.y(it)) } * 2 >= idx.size && idx.isNotEmpty()
                }
            }
            if (inside) selected.add(a.id)
        }
        selectionPage = if (selected.isEmpty()) -1 else lassoPage
        listener?.onSelectionChanged(selected.size)
        mode = Mode.NONE
    }

    private fun finishMoveSelection() {
        val st = store
        val dx = selDx
        val dy = selDy
        selDx = 0f
        selDy = 0f
        mode = Mode.NONE
        if (st == null || (abs(dx) < 0.5f && abs(dy) < 0.5f)) return
        val old = selected.mapNotNull { st.find(it) }
        if (old.isNotEmpty()) st.commit(AnnotationStore.Change(old, old.map { it.moveBy(dx, dy) }))
    }

    private fun pointInPolygon(poly: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    companion object {
        private const val MIN_ZOOM = 0.5f
        private const val MAX_ZOOM = 8f
        private const val MAX_BASE_PX = 1400f

        /** Margen entre el texto y el marco del cuadro, en puntos. */
        private const val FRAME_PAD = 3f

        /** Ancho mínimo de un cuadro de texto, en puntos. */
        private const val MIN_BOX_WIDTH = 24f

        /** Color del marco y las manijas del cuadro de texto elegido. */
        private const val TEXT_ACCENT = 0xFF5B4BC4.toInt()
    }
}
