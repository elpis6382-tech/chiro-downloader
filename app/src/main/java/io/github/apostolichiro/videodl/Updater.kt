package io.github.apostolichiro.videodl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import io.github.apostolichiro.videodl.engine.Versions
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(val versionCode: Int, val title: String, val apkUrl: String, val size: Long)

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val fraction: Float?) : UpdateState
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val release: AppRelease, val message: String) : UpdateState
}

/** Keeps the app itself up to date from its GitHub releases, so there's no need to open the link again. */
object Updater {
    const val REPO = "chiro11111/chiro-video"
    private const val API = "https://api.github.com/repos/$REPO/releases/tags/latest"
    private const val APK = "video-downloader.apk"

    /** Looks for a newer release, at most every [maxAgeMs]. Never throws. */
    fun check(ctx: Context, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        if (now - Store.updateCheckedAt < maxAgeMs) return
        if (Store.update.value is UpdateState.Downloading || Store.update.value is UpdateState.Ready) return
        try {
            val json = JSONObject(get(API))
            val code = Versions.parse(json.optString("body")) ?: Versions.parse(json.optString("name")) ?: return
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == APK } ?: return
            Store.updateCheckedAt = now
            val installed = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
            Store.update.value = if (code > installed) {
                UpdateState.Available(AppRelease(code, json.optString("name"), apk.getString("browser_download_url"), apk.optLong("size")))
            } else UpdateState.None
        } catch (e: Exception) {
            Log.w("VideoDl", "update check failed", e)
        }
    }

    /** Downloads the new APK in the background, reporting progress through [Store.update]. */
    fun download(ctx: Context, release: AppRelease) {
        Thread {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "video-downloader-${release.versionCode}.apk")
            try {
                Store.update.value = UpdateState.Downloading(release, 0f)
                var c = open(release.apkUrl)
                var hops = 0
                while (c.responseCode in 300..399 && hops++ < 5) {
                    val next = c.getHeaderField("Location") ?: break
                    c.disconnect()
                    c = open(URL(URL(release.apkUrl), next).toString())
                }
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: release.size
                var done = 0L
                var lastReport = 0L
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > (1 shl 20)) {
                                lastReport = done
                                Store.update.value = UpdateState.Downloading(release, if (total > 0) done.toFloat() / total else null)
                            }
                        }
                    }
                }
                c.disconnect()
                if (total > 0 && file.length() != total) throw IOException("download cut short")
                Store.update.value = UpdateState.Ready(release, file)
            } catch (e: Exception) {
                file.delete()
                Store.update.value = UpdateState.Failed(release, "새 버전을 받지 못했어요. 인터넷을 확인하고 다시 눌러 주세요.")
            }
        }.start()
    }

    /** Opens Android's installer for the downloaded APK (after asking to allow this app as a source, once). */
    fun install(activity: Activity, file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            Store.notice.value = "'이 출처 허용'을 켜고 돌아와서 [설치하기]를 한 번 더 눌러 주세요."
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "video-downloader-android")
        setRequestProperty("Accept", "application/vnd.github+json, */*")
    }

    private fun get(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
