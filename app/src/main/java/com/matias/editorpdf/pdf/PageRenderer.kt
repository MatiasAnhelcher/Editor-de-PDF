package com.matias.editorpdf.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.SizeF
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.ceil

/** Dibuja páginas del PDF en un hilo aparte, usando el motor de PDF de Android. */
class PageRenderer(file: File) : Closeable {

    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw e
    }
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Tamaño de cada página en puntos (con la rotación ya aplicada). Llamar fuera del hilo principal. */
    fun readPageSizes(): List<SizeF> = synchronized(lock) {
        (0 until pageCount).map { i ->
            renderer.openPage(i).use { SizeF(it.width.toFloat(), it.height.toFloat()) }
        }
    }

    /**
     * Dibuja la zona [region] (en puntos) de la página a [scale] píxeles por punto.
     * [isNeeded] se consulta justo antes de dibujar, para saltar pedidos que ya no hacen falta.
     */
    fun render(page: Int, region: RectF, scale: Float, isNeeded: () -> Boolean, callback: (Bitmap?) -> Unit) {
        if (closed) return
        try {
            executor.execute {
                val bmp = if (closed || !isNeeded()) null else renderSync(page, region, scale)
                main.post { if (!closed) callback(bmp) }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Dibuja ya, en el hilo que llama (no usar en el hilo principal). */
    fun renderSync(page: Int, region: RectF, scale: Float): Bitmap? = try {
        synchronized(lock) {
            if (closed) return null
            val w = ceil(region.width() * scale).toInt().coerceIn(1, 8192)
            val h = ceil(region.height() * scale).toInt().coerceIn(1, 8192)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.setTranslate(-region.left, -region.top)
            m.postScale(scale, scale)
            renderer.openPage(page).use { it.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            bmp
        }
    } catch (_: Throwable) {
        null
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            executor.execute {
                synchronized(lock) {
                    runCatching { renderer.close() }
                    runCatching { pfd.close() }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
        executor.shutdown()
    }
}
