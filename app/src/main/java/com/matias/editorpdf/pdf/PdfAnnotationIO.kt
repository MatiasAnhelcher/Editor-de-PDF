package com.matias.editorpdf.pdf

import android.graphics.Color
import android.graphics.RectF
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.InkAnnotation
import com.matias.editorpdf.model.InkKind
import com.matias.editorpdf.model.TextAnnotation
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * Lee y escribe nuestras anotaciones como anotaciones PDF estándar (Ink para trazos, Stamp para texto),
 * con su apariencia dibujada, para que se vean en cualquier lector (Adobe, Xodo, Drive, la PC...).
 * Cada anotación lleva además una copia de nuestros datos (clave /EdPdfData) para poder seguir
 * editándola al volver a abrir el PDF en esta app.
 */
object PdfAnnotationIO {

    private val KEY_DATA: COSName = COSName.getPDFName("EdPdfData")
    private const val NM_PREFIX = "edpdf-"
    const val HIGHLIGHT_ALPHA = 0.4f

    private val FONT_CANDIDATES = listOf(
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
    )

    fun read(file: File): List<Annotation> {
        val result = ArrayList<Annotation>()
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            var index = 0
            for (page in doc.pages) {
                val annots = runCatching { page.annotations }.getOrDefault(emptyList())
                for (a in annots) {
                    val json = a.cosObject.getString(KEY_DATA) ?: continue
                    runCatching { Annotation.fromJson(JSONObject(json))?.withPage(index) }
                        .getOrNull()?.let(result::add)
                }
                index++
            }
        }
        return result
    }

    fun write(src: File, dest: File, annotations: List<Annotation>, embedFont: Boolean) {
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
            val byPage = annotations.groupBy { it.page }
            val fonts = FontProvider(doc, embedFont)
            var index = 0
            for (page in doc.pages) {
                val existing = runCatching { page.annotations }.getOrDefault(emptyList())
                val mine = byPage[index].orEmpty()
                if (mine.isNotEmpty() || existing.any { isOurs(it) }) {
                    val list = ArrayList<PDAnnotation>(existing.filterNot { isOurs(it) })
                    val geometry = Affine.forPage(page)
                    for (a in mine) {
                        val built = when (a) {
                            is InkAnnotation -> buildInk(doc, page, geometry, a)
                            is TextAnnotation -> if (a.sticky) buildNote(page, geometry, a) else buildText(doc, page, geometry, a, fonts)
                        }
                        if (built != null) list.add(built)
                    }
                    page.annotations = list
                }
                index++
            }
            doc.save(dest)
        }
    }

    private fun isOurs(a: PDAnnotation): Boolean = a.cosObject.containsKey(KEY_DATA)

    private fun buildInk(doc: PDDocument, page: PDPage, g: Affine, a: InkAnnotation): PDAnnotation? {
        if (a.pointCount == 0) return null
        val box = RectF(a.bounds)
        box.inset(-1f, -1f)
        val rect = g.mapRect(box)

        val appearance = PDAppearanceStream(doc)
        appearance.setBBox(rect)
        appearance.setResources(PDResources())
        PDPageContentStream(doc, appearance).use { cs ->
            cs.transform(g.toMatrix())
            cs.setStrokingColor(red(a.color), green(a.color), blue(a.color))
            if (a.kind == InkKind.HIGHLIGHTER) {
                val gs = PDExtendedGraphicsState()
                gs.cosObject.setFloat(COSName.getPDFName("CA"), HIGHLIGHT_ALPHA)
                gs.cosObject.setItem(COSName.getPDFName("BM"), COSName.getPDFName("Multiply"))
                cs.setGraphicsStateParameters(gs)
            }
            cs.setLineJoinStyle(1)
            cs.setLineCapStyle(if (a.kind == InkKind.HIGHLIGHTER && a.straight) 0 else 1)
            if (a.kind == InkKind.PEN) {
                for (run in a.penRuns()) {
                    cs.setLineWidth(run.width)
                    cs.moveTo(a.x(run.from), a.y(run.from))
                    if (run.from == run.to) {
                        cs.lineTo(a.x(run.from) + 0.01f, a.y(run.from))
                    } else {
                        for (i in run.from + 1..run.to) cs.lineTo(a.x(i), a.y(i))
                    }
                    cs.stroke()
                }
            } else {
                val idx = a.drawnIndices()
                cs.setLineWidth(a.width)
                cs.moveTo(a.x(idx[0]), a.y(idx[0]))
                if (idx.size == 1) cs.lineTo(a.x(idx[0]) + 0.01f, a.y(idx[0]))
                for (k in 1 until idx.size) cs.lineTo(a.x(idx[k]), a.y(idx[k]))
                cs.stroke()
            }
        }

        val dict = baseDict(page, "Ink", rect, a)
        val path = COSArray()
        for (i in a.drawnIndices()) {
            path.add(COSFloat(g.x(a.x(i), a.y(i))))
            path.add(COSFloat(g.y(a.x(i), a.y(i))))
        }
        val inkList = COSArray()
        inkList.add(path)
        dict.setItem(COSName.getPDFName("InkList"), inkList)
        dict.setItem(COSName.getPDFName("C"), colorArray(a.color))
        val border = COSDictionary()
        border.setFloat(COSName.getPDFName("W"), a.width)
        dict.setItem(COSName.getPDFName("BS"), border)
        setAppearance(dict, appearance)
        return PDAnnotation.createAnnotation(dict)
    }

    private fun buildText(doc: PDDocument, page: PDPage, g: Affine, t: TextAnnotation, fonts: FontProvider): PDAnnotation? {
        if (t.text.isBlank()) return null
        val font = fonts.font()
        val lines = t.lines.map { fonts.sanitize(it) }
        val widths = lines.map { line ->
            runCatching { font.getStringWidth(line) / 1000f * t.size }.getOrDefault(line.length * t.size * 0.5f)
        }
        val w = if (t.boxWidth > 0f) t.boxWidth else max(widths.maxOrNull() ?: 0f, t.size * 0.5f)
        val h = (lines.size - 1) * t.size * TextAnnotation.LINE_SPACING + t.size * 1.2f
        val rect = g.mapRect(RectF(t.x - 1f, t.y - 1f, t.x + w + 1f, t.y + h + 1f))

        val appearance = PDAppearanceStream(doc)
        appearance.setBBox(rect)
        appearance.setResources(PDResources())
        PDPageContentStream(doc, appearance).use { cs ->
            cs.transform(g.toMatrix())
            cs.setNonStrokingColor(red(t.color), green(t.color), blue(t.color))
            cs.beginText()
            cs.setFont(font, t.size)
            lines.forEachIndexed { i, line ->
                // La página "de pantalla" tiene el eje Y hacia abajo: invertimos para que el texto quede derecho.
                cs.setTextMatrix(Matrix(1f, 0f, 0f, -1f, t.x, t.baseline(i)))
                cs.showText(line)
            }
            cs.endText()
        }

        val dict = baseDict(page, "Stamp", rect, t)
        dict.setString(COSName.getPDFName("Contents"), t.text)
        dict.setItem(COSName.getPDFName("C"), colorArray(t.color))
        setAppearance(dict, appearance)
        return PDAnnotation.createAnnotation(dict)
    }

    /** Nota adhesiva como comentario PDF estándar (/Text): Adobe y los demás lectores la muestran con su propio ícono. */
    private fun buildNote(page: PDPage, g: Affine, t: TextAnnotation): PDAnnotation {
        val rect = g.mapRect(RectF(t.bounds))
        val dict = baseDict(page, "Text", rect, t)
        dict.setString(COSName.getPDFName("Contents"), t.text)
        dict.setItem(COSName.getPDFName("C"), colorArray(t.color))
        dict.setItem(COSName.getPDFName("Name"), COSName.getPDFName("Comment"))
        dict.setBoolean(COSName.getPDFName("Open"), false)
        return PDAnnotation.createAnnotation(dict)
    }

    private fun baseDict(page: PDPage, subtype: String, rect: PDRectangle, a: Annotation): COSDictionary {
        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.getPDFName("Annot"))
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName(subtype))
        dict.setItem(COSName.RECT, rect.cosArray)
        dict.setInt(COSName.getPDFName("F"), 4) // Imprimible
        dict.setString(COSName.getPDFName("NM"), NM_PREFIX + a.id)
        dict.setString(COSName.getPDFName("T"), "Cosmos Editor de PDF")
        dict.setItem(COSName.getPDFName("P"), page.cosObject)
        dict.setString(KEY_DATA, a.toJson().toString())
        return dict
    }

    private fun setAppearance(dict: COSDictionary, appearance: PDAppearanceStream) {
        val ap = COSDictionary()
        ap.setItem(COSName.getPDFName("N"), appearance.cosObject)
        dict.setItem(COSName.getPDFName("AP"), ap)
    }

    private fun colorArray(color: Int): COSArray = COSArray().apply {
        add(COSFloat(red(color)))
        add(COSFloat(green(color)))
        add(COSFloat(blue(color)))
    }

    private fun red(c: Int) = Color.red(c) / 255f
    private fun green(c: Int) = Color.green(c) / 255f
    private fun blue(c: Int) = Color.blue(c) / 255f

    /** Transformación de coordenadas "de pantalla" de la página a coordenadas PDF (según CropBox y rotación). */
    private class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
        fun x(px: Float, py: Float) = a * px + c * py + e
        fun y(px: Float, py: Float) = b * px + d * py + f
        fun toMatrix() = Matrix(a, b, c, d, e, f)

        fun mapRect(r: RectF): PDRectangle {
            val xs = floatArrayOf(x(r.left, r.top), x(r.right, r.top), x(r.left, r.bottom), x(r.right, r.bottom))
            val ys = floatArrayOf(y(r.left, r.top), y(r.right, r.top), y(r.left, r.bottom), y(r.right, r.bottom))
            val minX = xs.min()
            val minY = ys.min()
            return PDRectangle(minX, minY, max(xs.max() - minX, 0.1f), max(ys.max() - minY, 0.1f))
        }

        companion object {
            fun forPage(page: PDPage): Affine {
                val box = page.cropBox
                val llx = box.lowerLeftX
                val lly = box.lowerLeftY
                val w = box.width
                val h = box.height
                return when (((page.rotation % 360) + 360) % 360) {
                    90 -> Affine(0f, 1f, 1f, 0f, llx, lly)
                    180 -> Affine(-1f, 0f, 0f, 1f, llx + w, lly)
                    270 -> Affine(0f, -1f, -1f, 0f, llx + w, lly + h)
                    else -> Affine(1f, 0f, 0f, -1f, llx, lly + h)
                }
            }
        }
    }

    /** Usa la fuente del sistema (con tildes y ñ) y, si no se puede, Helvetica. */
    private class FontProvider(private val doc: PDDocument, private val embed: Boolean) {
        private var cached: PDFont? = null

        fun font(): PDFont {
            cached?.let { return it }
            val f = (if (embed) loadSystemFont() else null) ?: PDType1Font.HELVETICA
            cached = f
            return f
        }

        private fun loadSystemFont(): PDFont? {
            for (path in FONT_CANDIDATES) {
                val file = File(path)
                if (!file.exists()) continue
                try {
                    return PDType0Font.load(doc, file)
                } catch (_: Exception) {
                }
            }
            return null
        }

        fun sanitize(s: String): String {
            val f = font()
            val sb = StringBuilder()
            var i = 0
            val text = s.replace('\t', ' ').replace("\r", "")
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val ch = String(Character.toChars(cp))
                sb.append(if (canEncode(f, ch)) ch else "?")
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        private fun canEncode(f: PDFont, ch: String): Boolean = try {
            f.encode(ch)
            true
        } catch (_: Exception) {
            false
        }
    }
}
