package com.cozyswitch.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cozyswitch.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateStage { Idle, Downloading, NeedsPermission, Ready, Installing, Failed }

/**
 * Looks on GitHub Releases for a newer build and, if there is one, downloads it and hands it
 * to Android's installer. It looks when the app opens, whenever it comes back to the
 * foreground, and every 10 minutes while open. Without internet it quietly does nothing.
 */
object Updater {
    private const val REPO = "ShinobiSondre/cozy-switch"
    private const val EVERY_MS = 10 * 60 * 1000L
    private const val MIN_GAP_MS = 30 * 1000L // GitHub allows 60 checks/hour without login

    var stage by mutableStateOf(UpdateStage.Idle); private set
    var currentBuild by mutableIntStateOf(BuildConfig.VERSION_CODE); private set
    var newBuild by mutableIntStateOf(0); private set
    var notes by mutableStateOf(""); private set
    var progress by mutableFloatStateOf(0f); private set
    var totalBytes by mutableLongStateOf(0L); private set
    var error by mutableStateOf<String?>(null); private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var apk: File? = null
    private var checking = false
    private var cancelled = false
    private var skipped = 0
    private var lastCheck = 0L
    private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            while (isActive) {
                check(app)
                delay(EVERY_MS)
            }
        }
    }

    /** Called from Activity.onResume. */
    fun onResume(ctx: Context) {
        val app = ctx.applicationContext
        when (stage) {
            UpdateStage.NeedsPermission -> installNow(app) // back from the settings switch
            UpdateStage.Installing -> stage = UpdateStage.Ready // back from installer without updating
            else -> scope.launch { check(app) }
        }
    }

    suspend fun check(ctx: Context) {
        if (checking || stage != UpdateStage.Idle) return
        val now = System.currentTimeMillis()
        if (now - lastCheck < MIN_GAP_MS) return
        lastCheck = now
        checking = true
        try {
            val r = latestRelease() ?: return
            if (r.build <= currentBuild || r.build <= skipped) return
            newBuild = r.build
            notes = r.notes
            totalBytes = r.size
            download(ctx, r.url, r.size)
        } catch (_: Exception) {
            if (stage == UpdateStage.Downloading) fail("The download stopped. Try again later.")
        } finally {
            checking = false
        }
    }

    /** The "Check for update" button. Returns a short message when there's nothing to install. */
    suspend fun checkNow(ctx: Context): String? {
        if (stage != UpdateStage.Idle) return null
        skipped = 0
        lastCheck = 0L
        val r = try { latestRelease() } catch (_: Exception) { null }
            ?: return "Couldn't reach GitHub. Check your internet."
        if (r.build <= currentBuild) return "You have the newest version."
        check(ctx.applicationContext)
        return null
    }

    internal data class Release(val build: Int, val url: String, val size: Long, val notes: String)

    internal suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 6000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "CozySwitch")
            if (c.responseCode != 200) return@withContext null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            // Tags look like v1.0.<build>; the build is the last number.
            val build = Regex("(\\d+)").findAll(j.optString("tag_name")).lastOrNull()?.value?.toIntOrNull()
                ?: return@withContext null
            val assets = j.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return@withContext Release(
                        build, a.getString("browser_download_url"), a.optLong("size"),
                        j.optString("body").lineSequence().firstOrNull()?.trim().orEmpty()
                    )
                }
            }
            null
        } finally { c.disconnect() }
    }

    private suspend fun download(ctx: Context, url: String, size: Long) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "cozyswitch-build-$newBuild.apk")
        dir.listFiles()?.filter { it != file }?.forEach { it.delete() } // old downloads
        if (file.exists() && file.length() == size) {
            progress = 1f; install(ctx, file); return
        }
        cancelled = false
        progress = 0f
        stage = UpdateStage.Downloading
        val part = File(dir, file.name + ".part")
        val ok = withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                var u = URL(url)
                // GitHub redirects release downloads to another host; follow manually.
                repeat(5) {
                    conn = (u.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 8000; readTimeout = 20000
                        setRequestProperty("User-Agent", "CozySwitch")
                    }
                    val code = conn!!.responseCode
                    if (code in 300..399) {
                        u = URL(u, conn!!.getHeaderField("Location")); conn!!.disconnect()
                    } else return@repeat
                }
                val c = conn!!
                if (c.responseCode != 200) return@withContext false
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else size
                var got = 0L
                var shown = 0f
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled) break
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val p = if (total > 0) got.toFloat() / total else 0f
                            if (p - shown >= 0.01f) {
                                shown = p
                                withContext(Dispatchers.Main) { progress = p }
                            }
                        }
                    }
                }
                if (cancelled) { part.delete(); return@withContext false }
                if (total > 0 && part.length() != total) { part.delete(); return@withContext false }
                part.renameTo(file)
            } catch (_: Exception) {
                part.delete(); false
            } finally { conn?.disconnect() }
        }
        when {
            cancelled -> stage = UpdateStage.Idle
            !ok -> fail("The download stopped. Try again later.")
            else -> { progress = 1f; install(ctx, file) }
        }
    }

    internal fun canInstall(ctx: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    private fun install(ctx: Context, file: File) {
        apk = file
        if (!canInstall(ctx)) { stage = UpdateStage.NeedsPermission; return }
        stage = UpdateStage.Installing
        // Installs through Android's package installer. After the first update this way,
        // Android 12+ lets later updates install by themselves, without a tap.
        if (!Installer.install(ctx, file)) fail("The update couldn't start.")
    }

    internal fun fail(msg: String) { error = msg; stage = UpdateStage.Failed }

    /** Opens Android's "install unknown apps" switch for Cozy Switch. */
    fun allowInstalls(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun installNow(ctx: Context) { apk?.let { install(ctx, it) } }

    /** Hides this update until the app restarts (or an even newer build appears). */
    fun later() {
        cancelled = true
        if (stage != UpdateStage.Failed) skipped = newBuild
        stage = UpdateStage.Idle
    }
}
