package com.matias.editorpdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Registro diario de estudio: minutos con la app en uso y páginas distintas leídas.
 * Alimenta la racha, la meta del día y el gráfico de la semana en la biblioteca.
 */
class StudyStats(context: Context) {

    class Day(val date: LocalDate, val seconds: Int, val pages: Int) {
        val minutes: Int get() = seconds / 60
    }

    private val prefs = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    var goalMinutes: Int
        get() = prefs.getInt("goal", 30)
        set(value) = prefs.edit().putInt("goal", value).apply()

    private fun load(): JSONObject = runCatching { JSONObject(prefs.getString("days", "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun save(o: JSONObject) {
        // Conservamos solo los últimos 400 días.
        val cutoff = LocalDate.now().minusDays(400).toString()
        o.keys().asSequence().toList().filter { it < cutoff }.forEach { o.remove(it) }
        prefs.edit().putString("days", o.toString()).apply()
    }

    private fun dayObject(o: JSONObject, date: LocalDate): JSONObject =
        o.optJSONObject(date.toString()) ?: JSONObject().also { o.put(date.toString(), it) }

    fun addSeconds(seconds: Int) {
        val o = load()
        val d = dayObject(o, LocalDate.now())
        d.put("sec", d.optInt("sec") + seconds)
        save(o)
    }

    /** Anota una página vista hoy ([doc] identifica el PDF). */
    fun markPage(doc: String, page: Int) {
        val o = load()
        val d = dayObject(o, LocalDate.now())
        val arr = d.optJSONArray("pg") ?: JSONArray().also { d.put("pg", it) }
        val key = "${doc.hashCode()}:$page"
        for (i in 0 until arr.length()) if (arr.optString(i) == key) return
        arr.put(key)
        save(o)
    }

    fun day(date: LocalDate): Day {
        val d = load().optJSONObject(date.toString())
        return Day(date, d?.optInt("sec") ?: 0, d?.optJSONArray("pg")?.length() ?: 0)
    }

    fun today(): Day = day(LocalDate.now())

    /** Los últimos 7 días, del más viejo a hoy. */
    fun week(): List<Day> {
        val o = load()
        val today = LocalDate.now()
        return (6 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val d = o.optJSONObject(date.toString())
            Day(date, d?.optInt("sec") ?: 0, d?.optJSONArray("pg")?.length() ?: 0)
        }
    }

    /** Días seguidos estudiando al menos [MIN_MINUTES]. Si hoy todavía no llegó, cuenta desde ayer. */
    fun streak(): Int {
        val o = load()
        fun studied(date: LocalDate) = (o.optJSONObject(date.toString())?.optInt("sec") ?: 0) >= MIN_MINUTES * 60
        var date = LocalDate.now()
        if (!studied(date)) date = date.minusDays(1)
        var n = 0
        while (studied(date)) {
            n++
            date = date.minusDays(1)
        }
        return n
    }

    companion object {
        const val MIN_MINUTES = 5
        val GOALS = listOf(15, 30, 45, 60, 90, 120)
    }
}
