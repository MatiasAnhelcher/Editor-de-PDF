package com.matias.editorpdf

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Busca versiones nuevas en los Releases de GitHub, descarga el APK y lo instala con el instalador
 * de Android. Cada versión publicada lleva la etiqueta v1.0.<número>, donde el número es el versionCode.
 */
class Updater(private val context: Context) {

    class Release(val versionCode: Long, val name: String, val notes: String, val apkUrl: String, val size: Long)

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    val currentVersionCode: Long
        get() = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    val currentVersionName: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Para la búsqueda automática al abrir la app: como mucho cada [AUTO_INTERVAL]. */
    fun shouldAutoCheck(): Boolean =
        System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) > AUTO_INTERVAL

    /** Devuelve la versión publicada si es más nueva que la instalada; null si ya está al día. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val conn = open(LATEST_URL)
        val json = try {
            if (conn.responseCode != 200) throw IOException("GitHub respondió ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = tag.substringAfterLast('.').toLongOrNull() ?: return@withContext null
        if (code <= currentVersionCode) return@withContext null
        val assets = o.optJSONArray("assets") ?: return@withContext null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") } ?: return@withContext null
        Release(
            versionCode = code,
            name = tag.removePrefix("v"),
            notes = o.optString("body"),
            apkUrl = apk.getString("browser_download_url"),
            size = apk.optLong("size"),
        )
    }

    /** Descarga el APK a la caché; [onProgress] recibe 0..1 (o -1 si no se sabe el tamaño). */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "EditorDePDF-${release.name}.apk")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) throw IOException("La descarga respondió ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            var read = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (read - lastReport > 256 * 1024) {
                            lastReport = read
                            val p = if (total > 0) read.toFloat() / total else -1f
                            withContext(Dispatchers.Main) { onProgress(p) }
                        }
                    }
                }
            }
            if (total > 0 && read != total) throw IOException("La descarga quedó incompleta")
        } catch (e: Throwable) {
            file.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        file
    }

    /** Android pide una vez permiso para que esta app instale actualizaciones. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Entrega el APK al instalador del sistema. La app se cierra sola cuando termina de actualizarse. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Desde la segunda actualización Android puede instalar sin preguntar.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "EditorDePDF-Android")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/MatiasAnhelcher/Editor-de-PDF/releases/latest"
        const val KEY_LAST_CHECK = "last_check"
        private const val AUTO_INTERVAL = 60L * 60 * 1000
    }
}

/** Recibe el resultado del instalador: si Android necesita confirmación, muestra su pantalla. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, context.getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }
}
