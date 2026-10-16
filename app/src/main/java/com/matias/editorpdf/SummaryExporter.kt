package com.matias.editorpdf

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.InkAnnotation
import com.matias.editorpdf.model.InkKind
import com.matias.editorpdf.model.TextAnnotation
import java.io.OutputStream

/** Arma un PDF de resumen con todos los resaltados, subrayados y notas, en orden de página. */
object SummaryExporter {

    class Item(val annotation: Annotation, val text: String, val label: String)

    private const val W = 595
    private const val H = 842
    private const val M = 48f

    fun write(out: OutputStream, title: String, items: List<Item>) {
        val doc = PdfDocument()
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            isFakeBoldText = true
        }
        val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 9f
            color = 0xFF616161.toInt()
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val width = (W - 2 * M - 16).toInt()

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }

        fun layout(text: String, paint: TextPaint, w: Int): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build()

        newPage()
        val t = layout(title, titlePaint, (W - 2 * M).toInt())
        page!!.canvas.apply {
            save(); translate(M, y); t.draw(this); restore()
        }
        y += t.height + 6f
        val sub = layout("Resumen de resaltados y notas · ${items.size} elementos", metaPaint, (W - 2 * M).toInt())
        page!!.canvas.apply {
            save(); translate(M, y); sub.draw(this); restore()
        }
        y += sub.height + 18f

        var lastPage = -1
        for (item in items) {
            val a = item.annotation
            val header = if (a.page != lastPage) layout("Página ${a.page + 1}", titlePaint.apply { textSize = 13f }, width) else null
            val meta = layout(item.label, metaPaint, width)
            val body = layout(item.text.ifBlank { "(sin texto: PDF escaneado o trazo libre)" }, bodyPaint, width)
            val needed = (header?.height ?: 0) + meta.height + body.height + 22f
            if (y + needed > H - M) newPage()
            val c = page!!.canvas
            if (header != null) {
                c.save(); c.translate(M, y); header.draw(c); c.restore()
                y += header.height + 6f
                lastPage = a.page
            }
            boxPaint.color = a.color or 0xFF000000.toInt()
            c.drawRect(M, y + 2f, M + 8f, y + meta.height + body.height + 2f, boxPaint)
            c.save(); c.translate(M + 16f, y); meta.draw(c); c.restore()
            y += meta.height + 2f
            c.save(); c.translate(M + 16f, y); body.draw(c); c.restore()
            y += body.height + 14f
        }
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }

    fun kindName(a: Annotation): String = when {
        a is TextAnnotation -> "Nota"
        a is InkAnnotation && a.kind == InkKind.UNDERLINE -> "Subrayado"
        a is InkAnnotation && a.kind == InkKind.STRIKE -> "Tachado"
        else -> "Resaltado"
    }
}
