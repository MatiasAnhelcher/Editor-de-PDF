package com.matias.editorpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.matias.editorpdf.pdf.PageRenderer
import java.io.File
import java.security.MessageDigest

/** Portadas de la biblioteca: una imagen chica de la primera página de cada PDF. */
object Covers {

    private const val WIDTH_PX = 360

    private fun file(context: Context, uri: String): File {
        val key = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "covers").apply { mkdirs() }, "$key.jpg")
    }

    /** Guarda la portada si todavía no existe. Llamar fuera del hilo principal. */
    fun ensure(context: Context, uri: String, renderer: PageRenderer, width: Float, height: Float) {
        val f = file(context, uri)
        if (f.exists() || width <= 0f) return
        val bmp = renderer.renderSync(0, RectF(0f, 0f, width, height), WIDTH_PX / width) ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(f)
        }
        bmp.recycle()
    }

    fun load(context: Context, uri: String): Bitmap? {
        val f = file(context, uri)
        return if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
    }

    fun delete(context: Context, uri: String) {
        file(context, uri).delete()
    }
}
