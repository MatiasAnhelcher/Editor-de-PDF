package com.matias.editorpdf.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.matias.editorpdf.model.Annotation
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** El archivo no permite escritura (por ejemplo, se abrió en modo solo lectura desde otra app). */
class WriteDeniedException(cause: Throwable) : IOException(cause.message, cause)

/**
 * Un PDF abierto. Trabajamos sobre una copia privada (para dibujar y leer sin depender del proveedor
 * del archivo) y al guardar escribimos el resultado sobre el archivo original.
 */
class DocumentSession(private val context: Context, val uri: Uri) {

    class Loaded(val annotations: List<Annotation>, val fromDraft: Boolean, val readError: Throwable?)

    private val key = sha1(uri.toString())
    private val docsDir = File(context.filesDir, "docs").apply { mkdirs() }
    private val draftsDir = File(context.filesDir, "drafts").apply { mkdirs() }

    val workFile = File(docsDir, "$key.pdf")
    private val draftFile = File(draftsDir, "$key.json")

    fun displayName(): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "documento.pdf"
    }

    /** Copia el PDF original a la carpeta privada de la app. */
    fun prepare() {
        val tmp = File(docsDir, "$key.tmp")
        val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        input.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        replace(tmp, workFile)
    }

    fun loadAnnotations(): Loaded {
        if (draftFile.exists()) {
            runCatching {
                val json = JSONObject(draftFile.readText())
                return Loaded(Annotation.listFromJson(json.getJSONArray("annotations")), true, null)
            }
        }
        return try {
            Loaded(PdfAnnotationIO.read(workFile), false, null)
        } catch (e: Throwable) {
            Loaded(emptyList(), false, e)
        }
    }

    /** Guarda las anotaciones sin guardar en un borrador interno, por si la app se cierra. */
    fun saveDraft(list: List<Annotation>) {
        val tmp = File(draftsDir, "$key.json.tmp")
        tmp.writeText(JSONObject().put("version", 1).put("annotations", Annotation.listToJson(list)).toString())
        replace(tmp, draftFile)
    }

    fun deleteDraft() {
        draftFile.delete()
    }

    /** Escribe las anotaciones en el PDF y lo guarda en [target] (el original o una copia). */
    fun save(list: List<Annotation>, target: Uri, expectedPages: Int) {
        val out = File(docsDir, "$key.saving")
        try {
            try {
                PdfAnnotationIO.write(workFile, out, list, embedFont = true)
            } catch (e: Exception) {
                PdfAnnotationIO.write(workFile, out, list, embedFont = false)
            }
            verify(out, expectedPages)
            try {
                writeToUri(out, target)
            } catch (e: Exception) {
                // Si llegamos a truncar el original, intentamos dejarlo como estaba.
                if (target == uri) runCatching { writeToUri(workFile, target) }
                throw if (e is SecurityException || e is FileNotFoundException || e is UnsupportedOperationException) {
                    WriteDeniedException(e)
                } else {
                    e
                }
            }
            if (target == uri) replace(out, workFile)
        } finally {
            out.delete()
        }
    }

    private fun verify(file: File, expectedPages: Int) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount != expectedPages) throw IOException("El PDF generado no es válido")
            }
        }
    }

    private fun writeToUri(src: File, target: Uri) {
        val cr = context.contentResolver
        val pfd = try {
            cr.openFileDescriptor(target, "rwt")
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (pfd != null) {
            pfd.use {
                FileOutputStream(it.fileDescriptor).use { out ->
                    out.channel.truncate(0)
                    src.inputStream().use { inp -> inp.copyTo(out) }
                    out.flush()
                    runCatching { out.fd.sync() }
                }
            }
        } else {
            val out = cr.openOutputStream(target, "wt") ?: throw FileNotFoundException(target.toString())
            out.use { o -> src.inputStream().use { inp -> inp.copyTo(o) } }
        }
    }

    private fun replace(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    companion object {
        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
