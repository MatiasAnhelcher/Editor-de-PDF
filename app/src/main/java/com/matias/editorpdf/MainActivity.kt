package com.matias.editorpdf

import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.animation.DecelerateInterpolator
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.matias.editorpdf.view.WeekBarsView
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import com.matias.editorpdf.view.AspectImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var recents: Recents
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private val adapter = RecentAdapter()
    private lateinit var updater: Updater
    private lateinit var stats: StudyStats
    private var statsAnimated = false
    private lateinit var updateBanner: View
    private lateinit var updateText: TextView
    private var available: Updater.Release? = null
    private var checking: Job? = null
    private var pendingInstall: Updater.Release? = null

    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets(findViewById(R.id.root))
        recents = Recents(this)
        list = findViewById(R.id.recent_list)
        empty = findViewById(R.id.empty)
        val widthDp = resources.configuration.screenWidthDp
        list.layoutManager = GridLayoutManager(this, (widthDp / 190).coerceIn(2, 8))
        list.adapter = adapter
        findViewById<View>(R.id.open_button).setOnClickListener {
            openLauncher.launch(arrayOf("application/pdf"))
        }

        stats = StudyStats(this)
        findViewById<View>(R.id.goal_box).setOnClickListener { chooseGoal() }
        findViewById<View>(R.id.reminder_button).setOnClickListener { chooseReminder() }
        Reminders.schedule(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Al terminar la animación de apertura, el logo se desvanece suavemente.
            splashScreen.setOnExitAnimationListener { splash ->
                splash.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setDuration(260)
                    .withEndAction { splash.remove() }.start()
            }
        }
        if (savedInstanceState == null) animateEntrance()

        updater = Updater(this)
        updateBanner = findViewById(R.id.update_banner)
        updateText = findViewById(R.id.update_text)
        findViewById<View>(R.id.update_button).setOnClickListener { available?.let { startUpdate(it) } }
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.main)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.action_updates -> checkForUpdates(manual = true)
                    R.id.action_reminder -> chooseReminder()
                }
                true
            }
        }
        if (savedInstanceState == null && updater.shouldAutoCheck()) checkForUpdates(manual = false)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Volvemos de dar el permiso de instalar: seguimos con la actualización.
        val r = pendingInstall
        if (r != null && updater.canInstall()) {
            pendingInstall = null
            startUpdate(r)
        }
    }

    // --- Actualizaciones -------------------------------------------------------------------

    private fun checkForUpdates(manual: Boolean) {
        if (checking?.isActive == true) return
        val dialog = if (manual) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.checking_updates)
                .setCancelable(true)
                .show()
        } else {
            null
        }
        checking = lifecycleScope.launch {
            val result = runCatching { updater.check() }
            dialog?.dismiss()
            result.onSuccess { release ->
                available = release
                showBanner()
                if (manual) {
                    if (release != null) {
                        askUpdate(release)
                    } else {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(R.string.check_updates)
                            .setMessage(getString(R.string.up_to_date, updater.currentVersionName))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }.onFailure { e ->
                if (manual) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.check_updates)
                        .setMessage(getString(R.string.update_check_failed, e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun showBanner() {
        val r = available
        updateBanner.isVisible = r != null
        if (r != null) updateText.text = getString(R.string.update_available, r.name, updater.currentVersionName)
    }

    private fun askUpdate(r: Updater.Release) {
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available, r.name, updater.currentVersionName))
            .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(r) }
            .setNegativeButton(R.string.update_later, null)
        // La primera línea de las notas es el título del cambio publicado.
        val notes = r.notes.trim().lineSequence().firstOrNull()?.trim()?.takeUnless { it.startsWith("Descarga") }.orEmpty()
        if (notes.isNotEmpty()) b.setMessage(getString(R.string.update_whats_new) + "\n\n" + notes)
        b.show()
    }

    private fun startUpdate(r: Updater.Release) {
        if (!updater.canInstall()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_permission_go) { _, _ ->
                    pendingInstall = r
                    runCatching { startActivity(updater.installPermissionIntent()) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        val status = view.findViewById<TextView>(R.id.update_status)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.update_progress)
        status.text = getString(R.string.update_downloading, r.name)
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.check_updates)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .show()
        job = lifecycleScope.launch {
            val result = runCatching {
                val apk = updater.download(r) { p ->
                    if (p >= 0f) {
                        if (bar.isIndeterminate) {
                            bar.visibility = View.INVISIBLE
                            bar.isIndeterminate = false
                            bar.visibility = View.VISIBLE
                        }
                        bar.setProgressCompat((p * 100).toInt(), true)
                        status.text = getString(R.string.update_downloading_percent, r.name, (p * 100).toInt())
                    }
                }
                status.text = getString(R.string.update_installing)
                updater.install(apk)
            }
            dialog.dismiss()
            result.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(R.string.check_updates)
                    .setMessage(getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private val covers = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Las secciones de la biblioteca entran escalonadas, de arriba hacia abajo. */
    private fun animateEntrance() {
        val ids = listOf(R.id.greeting, R.id.stats_card, R.id.continue_card, R.id.recent_header, R.id.recent_list)
        val shift = 24 * resources.displayMetrics.density
        ids.forEachIndexed { i, id ->
            val v = findViewById<View>(id)
            v.alpha = 0f
            v.translationY = shift
            v.animate().alpha(1f).translationY(0f).setStartDelay(120L + i * 70L).setDuration(420)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    private fun refreshStats() {
        val hour = LocalTime.now().hour
        findViewById<TextView>(R.id.greeting).text = getString(
            when (hour) {
                in 5..11 -> R.string.greeting_morning
                in 12..19 -> R.string.greeting_afternoon
                else -> R.string.greeting_night
            }
        )
        val streak = stats.streak()
        findViewById<TextView>(R.id.streak_value).text = streak.toString()
        findViewById<TextView>(R.id.streak_label).text =
            getString(if (streak == 1) R.string.streak_one_day else R.string.streak_days)
        findViewById<View>(R.id.streak_icon).alpha = if (streak > 0) 1f else 0.35f

        val today = stats.today()
        val goal = stats.goalMinutes
        findViewById<TextView>(R.id.goal_value).text = getString(R.string.goal_value, today.minutes, goal)
        findViewById<TextView>(R.id.goal_label).text = getString(R.string.goal_unit)
        val ring = findViewById<CircularProgressIndicator>(R.id.goal_ring)
        val target = (today.minutes * 1000 / goal.coerceAtLeast(1)).coerceAtMost(1000)
        if (!statsAnimated) ring.progress = 0
        ring.setProgressCompat(target, true)
        findViewById<TextView>(R.id.today_detail).text = when {
            today.minutes >= goal -> getString(R.string.today_detail_done, today.pages)
            today.minutes == 0 -> getString(R.string.today_detail_start, StudyStats.MIN_MINUTES)
            else -> getString(R.string.today_detail_pending, goal - today.minutes, today.pages)
        }
        val week = stats.week()
        val locale = Locale.getDefault()
        findViewById<WeekBarsView>(R.id.week_bars).setData(
            week.map { it.minutes },
            week.map { it.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale).uppercase(locale) },
            goal,
            animate = !statsAnimated,
        )
        findViewById<com.google.android.material.button.MaterialButton>(R.id.reminder_button).text =
            if (Reminders.isEnabled(this)) {
                getString(R.string.reminder_at, Reminders.time(this).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
            } else {
                getString(R.string.reminder_menu)
            }
        statsAnimated = true
    }

    // --- Recordatorio diario --------------------------------------------------------------------

    private var pendingReminder: java.time.LocalTime? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val at = pendingReminder ?: return@registerForActivityResult
            pendingReminder = null
            if (granted) saveReminder(at) else Snackbar.make(list, R.string.reminder_need_permission, Snackbar.LENGTH_LONG).show()
        }

    private fun chooseReminder() {
        val hours = listOf(8, 12, 15, 18, 20, 22)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val labels = listOf(getString(R.string.reminder_off)) +
            hours.map { getString(R.string.reminder_at, java.time.LocalTime.of(it, 0).format(fmt)) } +
            getString(R.string.reminder_other)
        val current = if (!Reminders.isEnabled(this)) 0 else {
            val t = Reminders.time(this)
            if (t.minute == 0 && t.hour in hours) hours.indexOf(t.hour) + 1 else labels.size - 1
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reminder_set)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> {
                        Reminders.disable(this)
                        refreshStats()
                    }
                    labels.size - 1 -> pickReminderTime()
                    else -> requestReminder(java.time.LocalTime.of(hours[which - 1], 0))
                }
            }
            .show()
    }

    private fun pickReminderTime() {
        val t = Reminders.time(this)
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
            .setHour(t.hour)
            .setMinute(t.minute)
            .setTitleText(R.string.reminder_set)
            .build()
        picker.addOnPositiveButtonClickListener {
            requestReminder(java.time.LocalTime.of(picker.hour, picker.minute))
        }
        picker.show(supportFragmentManager, "reminder_time")
    }

    private fun requestReminder(at: java.time.LocalTime) {
        if (Reminders.canNotify(this)) {
            saveReminder(at)
        } else if (Build.VERSION.SDK_INT >= 33) {
            pendingReminder = at
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun saveReminder(at: java.time.LocalTime) {
        Reminders.enable(this, at)
        refreshStats()
        val text = at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        Snackbar.make(list, getString(R.string.reminder_saved, text), Snackbar.LENGTH_LONG).show()
    }

    private fun chooseGoal() {
        val goals = StudyStats.GOALS
        val labels = goals.map { getString(R.string.goal_option, it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.goal_title)
            .setSingleChoiceItems(labels, goals.indexOf(stats.goalMinutes)) { d, which ->
                stats.goalMinutes = goals[which]
                d.dismiss()
                refreshStats()
            }
            .show()
    }

    private fun refresh() {
        refreshStats()
        val all = recents.list()
        val first = all.firstOrNull()
        adapter.items = all.drop(1)
        adapter.notifyDataSetChanged()
        empty.isVisible = all.isEmpty()
        findViewById<View>(R.id.recent_header).isVisible = adapter.items.isNotEmpty()
        val card = findViewById<View>(R.id.continue_card)
        card.isVisible = first != null
        if (first != null) {
            findViewById<TextView>(R.id.continue_title).text = first.name.ifBlank { getString(R.string.untitled) }
            findViewById<TextView>(R.id.continue_meta).text = describe(first)
            findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.continue_progress).apply {
                isVisible = first.pages > 0
                progress = progressOf(first)
            }
            loadCover(findViewById<AspectImageView>(R.id.continue_cover), first.uri)
            val go = View.OnClickListener { open(Uri.parse(first.uri), findViewById(R.id.continue_cover)) }
            card.setOnClickListener(go)
            card.setOnLongClickListener {
                confirmRemove(first)
                true
            }
            findViewById<View>(R.id.continue_button).setOnClickListener(go)
        }
    }

    private fun confirmRemove(item: RecentDoc) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setMessage(R.string.remove_recent_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                recents.remove(item.uri)
                Covers.delete(this, item.uri)
                covers.remove(item.uri)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun describe(d: RecentDoc): String {
        val ago = DateUtils.getRelativeTimeSpanString(d.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        return if (d.pages > 0) getString(R.string.page_of, d.page + 1, d.pages, ago)
        else getString(R.string.recent_subtitle, d.page + 1, ago)
    }

    private fun progressOf(d: RecentDoc): Int =
        if (d.pages <= 1) 0 else ((d.page.toFloat() / (d.pages - 1)) * 1000).toInt().coerceIn(0, 1000)

    private fun loadCover(view: ImageView, uri: String) {
        view.tag = uri
        val cached = covers.get(uri)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { Covers.load(applicationContext, uri) } ?: return@launch
            covers.put(uri, bmp)
            if (view.tag == uri) view.setImageBitmap(bmp)
        }
    }

    private fun open(uri: Uri, from: View? = null) {
        Uris.persist(this, uri)
        val intent = Intent(this, EditorActivity::class.java)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (from != null) {
            // El documento se abre "creciendo" desde su portada.
            from.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val options = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
            startActivity(intent, options.toBundle())
        } else {
            startActivity(intent)
        }
    }

    private inner class RecentAdapter : RecyclerView.Adapter<RecentAdapter.Holder>() {
        var items: List<RecentDoc> = emptyList()

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val cover: AspectImageView = v.findViewById(R.id.cover)
            val progress: com.google.android.material.progressindicator.LinearProgressIndicator = v.findViewById(R.id.progress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.name.ifBlank { getString(R.string.untitled) }
            holder.subtitle.text = describe(item)
            holder.progress.isVisible = item.pages > 0
            holder.progress.progress = progressOf(item)
            loadCover(holder.cover, item.uri)
            holder.itemView.setOnClickListener { open(Uri.parse(item.uri), holder.cover) }
            holder.itemView.setOnLongClickListener {
                confirmRemove(item)
                true
            }
        }
    }
}
