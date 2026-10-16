package com.matias.editorpdf.pdf

import android.util.SizeF
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.InkAnnotation
import com.matias.editorpdf.model.TextAnnotation
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File

/** Organizar páginas: girar, insertar una en blanco o eliminar, como en "Organizar páginas" de Acrobat. */
sealed class PageOp {
    /** Gira la página 90° a la derecha. */
    data class Rotate(val page: Int) : PageOp()

    /** Agrega una página en blanco (del mismo tamaño) después de [page]. */
    data class InsertBlank(val page: Int) : PageOp()

    data class Delete(val page: Int) : PageOp()
}

object PageOps {

    /** Aplica la operación sobre el archivo (lo reescribe entero, de forma segura). */
    fun apply(file: File, op: PageOp) {
        val tmp = File(file.parentFile, file.name + ".pages")
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
            when (op) {
                is PageOp.Rotate -> {
                    val p = doc.getPage(op.page)
                    p.rotation = ((p.rotation + 90) % 360 + 360) % 360
                }
                is PageOp.InsertBlank -> {
                    val ref = doc.getPage(op.page)
                    val box = ref.mediaBox
                    val blank = PDPage(PDRectangle(box.lowerLeftX, box.lowerLeftY, box.width, box.height))
                    blank.rotation = ref.rotation
                    doc.pages.insertAfter(blank, ref)
                }
                is PageOp.Delete -> doc.removePage(op.page)
            }
            doc.save(tmp)
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** Acomoda las anotaciones a las páginas nuevas (índices y, al girar, posición). */
    fun remap(list: List<Annotation>, op: PageOp, sizes: List<SizeF>): List<Annotation> = when (op) {
        is PageOp.Delete -> list.filter { it.page != op.page }.map { if (it.page > op.page) it.withPage(it.page - 1) else it }
        is PageOp.InsertBlank -> list.map { if (it.page > op.page) it.withPage(it.page + 1) else it }
        is PageOp.Rotate -> {
            // Girar a la derecha: el punto (x, y) de una hoja de alto h pasa a (h - y, x).
            val h = sizes.getOrNull(op.page)?.height ?: 0f
            list.map { a -> if (a.page == op.page) rotate(a, h) else a }
        }
    }

    private fun rotate(a: Annotation, h: Float): Annotation = when (a) {
        is InkAnnotation -> {
            val p = a.points.copyOf()
            for (i in 0 until a.pointCount) {
                val x = a.x(i)
                val y = a.y(i)
                p[i * 3] = h - y
                p[i * 3 + 1] = x
            }
            InkAnnotation(a.id, a.page, a.kind, a.color, a.width, p, a.straight)
        }
        is TextAnnotation -> {
            // El texto sigue derecho: solo se mueve su cuadro al lugar que le corresponde.
            val b = a.bounds
            a.moved((h - b.bottom) - a.x, b.left - a.y)
        }
    }
}
