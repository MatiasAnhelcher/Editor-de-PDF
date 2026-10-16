package com.matias.editorpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var recents: Recents
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private val adapter = RecentAdapter()
    private lateinit var updater: Updater
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
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        findViewById<View>(R.id.open_button).setOnClickListener {
            openLauncher.launch(arrayOf("application/pdf"))
        }

        updater = Updater(this)
        updateBanner = findViewById(R.id.update_banner)
        updateText = findViewById(R.id.update_text)
        findViewById<View>(R.id.update_button).setOnClickListener { available?.let { startUpdate(it) } }
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.main)
            setOnMenuItemClickListener {
                if (it.itemId == R.id.action_updates) checkForUpdates(manual = true)
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

    private fun refresh() {
        adapter.items = recents.list()
        adapter.notifyDataSetChanged()
        empty.isVisible = adapter.items.isEmpty()
    }

    private fun open(uri: Uri) {
        Uris.persist(this, uri)
        startActivity(
            Intent(this, EditorActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        )
    }

    private inner class RecentAdapter : RecyclerView.Adapter<RecentAdapter.Holder>() {
        var items: List<RecentDoc> = emptyList()

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.title.text = item.name.ifBlank { getString(R.string.untitled) }
            holder.subtitle.text = getString(
                R.string.recent_subtitle,
                item.page + 1,
                DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            holder.itemView.setOnClickListener { open(Uri.parse(item.uri)) }
            holder.itemView.setOnLongClickListener {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(item.name)
                    .setMessage(R.string.remove_recent_message)
                    .setPositiveButton(R.string.remove) { _, _ ->
                        recents.remove(item.uri)
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
    }
}
