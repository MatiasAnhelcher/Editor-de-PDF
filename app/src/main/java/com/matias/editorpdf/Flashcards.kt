package com.matias.editorpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.matias.editorpdf.model.Annotation
import com.matias.editorpdf.model.TextAnnotation
import com.matias.editorpdf.pdf.PageRenderer
import com.matias.editorpdf.view.AnnotationPainter
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/** Programación de repaso espaciado de cada tarjeta (una por resaltado, subrayado o nota). */
class CardSchedule(context: Context, private val uri: Uri) {
    private val prefs = context.getSharedPreferences("cards", Context.MODE_PRIVATE)

    class State(var due: Long, var interval: Double, var ease: Double, var reps: Int)

    private val states: MutableMap<String, State> = load()

    private fun load(): MutableMap<String, State> = runCatching {
        val o = JSONObject(prefs.getString(uri.toString(), "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val s = o.getJSONObject(k)
            State(s.optLong("due"), s.optDouble("ivl", 0.0), s.optDouble("ease", 2.5), s.optInt("reps"))
        }.toMutableMap()
    }.getOrDefault(HashMap())

    private fun save() {
        val o = JSONObject()
        states.forEach { (k, s) ->
            o.put(k, JSONObject().put("due", s.due).put("ivl", s.interval).put("ease", s.ease).put("reps", s.reps))
        }
        prefs.edit().putString(uri.toString(), o.toString()).apply()
    }

    fun isDue(id: String, now: Long = System.currentTimeMillis()) = (states[id]?.due ?: 0L) <= now

    fun isNew(id: String) = id !in states

    /** [grade]: 0 = otra vez, 1 = difícil, 2 = bien, 3 = fácil. */
    fun answer(id: String, grade: Int) {
        val s = states.getOrPut(id) { State(0, 0.0, 2.5, 0) }
        val now = System.currentTimeMillis()
        if (grade == 0) {
            s.reps = 0
            s.interval = 0.0
            s.ease = max(1.3, s.ease - 0.2)
            s.due = now + 60_000
        } else {
            val good = when (s.reps) {
                0 -> 1.0
                1 -> 3.0
                else -> s.interval * s.ease
            }
            s.interval = when (grade) {
                1 -> max(1.0, if (s.reps == 0) 1.0 else s.interval * 1.2)
                3 -> good * 1.3
                else -> good
            }
            s.ease = when (grade) {
                1 -> max(1.3, s.ease - 0.15)
                3 -> s.ease + 0.15
                else -> s.ease
            }
            s.reps++
            s.due = now + (s.interval * DAY).toLong()
        }
        save()
    }

    companion object {
        private const val DAY = 24.0 * 60 * 60 * 1000
    }
}

/**
 * Sesión de tarjetas: muestra el pedazo de página con lo resaltado tapado; al tocar
 * "Mostrar respuesta" se destapa y uno califica qué tan bien lo recordaba.
 */
class FlashcardSession(
    private val activity: AppCompatActivity,
    private val renderer: PageRenderer,
    private val pageWidth: (Int) -> Float,
    private val pageHeight: (Int) -> Float,
    private val pageAnnotations: (Int) -> List<Annotation>,
    private val schedule: CardSchedule,
    cards: List<Annotation>,
    private val onGoToPage: (Annotation) -> Unit,
) {
    private val queue = ArrayDeque(cards)
    private val total = cards.size
    private var done = 0
    private var current: Annotation? = null
    private val painter = AnnotationPainter()

    private val view: View = activity.layoutInflater.inflate(R.layout.dialog_flashcards, null)
    private val dialog = MaterialAlertDialogBuilder(activity).setView(view).create()
    private val progress: TextView = view.findViewById(R.id.card_progress)
    private val image: ImageView = view.findViewById(R.id.card_image)
    private val note: TextView = view.findViewById(R.id.card_note)
    private val showButton: View = view.findViewById(R.id.card_show)
    private val grades: View = view.findViewById(R.id.card_grades)
    private var front: Bitmap? = null
    private var back: Bitmap? = null

    fun start() {
        view.findViewById<View>(R.id.card_close).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.card_goto).setOnClickListener {
            current?.let(onGoToPage)
            dialog.dismiss()
        }
        showButton.setOnClickListener { reveal() }
        listOf(R.id.grade_again, R.id.grade_hard, R.id.grade_good, R.id.grade_easy).forEachIndexed { grade, id ->
            view.findViewById<View>(id).setOnClickListener { answer(grade) }
        }
        dialog.show()
        dialog.window?.setLayout(
            min(activity.resources.displayMetrics.widthPixels, (900 * activity.resources.displayMetrics.density).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        next()
    }

    private fun next() {
        val a = queue.removeFirstOrNull()
        current = a
        if (a == null) {
            progress.text = activity.getString(R.string.cards_done, done)
            image.setImageDrawable(null)
            image.isVisible = false
            note.isVisible = false
            showButton.isVisible = false
            grades.isVisible = false
            return
        }
        progress.text = activity.getString(R.string.cards_progress, done + 1, total, a.page + 1)
        showButton.isVisible = true
        grades.isVisible = false
        note.isVisible = false
        image.isVisible = true
        image.setImageDrawable(null)
        activity.lifecycleScope.launch {
            val pair = renderCard(a)
            if (current !== a) return@launch
            front = pair?.first
            back = pair?.second
            image.setImageBitmap(front)
        }
    }

    private fun reveal() {
        val a = current ?: return
        image.setImageBitmap(back)
        if (a is TextAnnotation) {
            note.text = a.text
            note.isVisible = true
        }
        showButton.isVisible = false
        grades.isVisible = true
    }

    private fun answer(grade: Int) {
        val a = current ?: return
        schedule.answer(a.id, grade)
        if (grade == 0) queue.addLast(a) else done++
        next()
    }

    /** Dibuja la zona de la página alrededor de la tarjeta: tapada (frente) y destapada (dorso). */
    private suspend fun renderCard(a: Annotation): Pair<Bitmap, Bitmap>? {
        val pw = pageWidth(a.page)
        val ph = pageHeight(a.page)
        val b = a.bounds
        val area = RectF(0f, max(0f, b.top - 70f), pw, min(ph, b.bottom + 70f))
        val targetPx = min(activity.resources.displayMetrics.widthPixels, 1600)
        val scale = targetPx / pw
        val bmp = suspendCancellableCoroutine<Bitmap?> { cont ->
            renderer.render(a.page, area, scale, isNeeded = { true }) { cont.resume(it) }
        } ?: return null
        val backBmp = bmp
        val frontBmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val others = pageAnnotations(a.page)
        fun paint(target: Bitmap, coverTarget: Boolean) {
            val c = Canvas(target)
            c.scale(scale, scale)
            c.translate(-area.left, -area.top)
            for (o in others) {
                if (o.id == a.id) continue
                painter.draw(c, o, cache = false)
            }
            if (coverTarget) painter.drawCover(c, a) else painter.draw(c, a, cache = false)
        }
        paint(frontBmp, true)
        paint(backBmp, false)
        return frontBmp to backBmp
    }
}
