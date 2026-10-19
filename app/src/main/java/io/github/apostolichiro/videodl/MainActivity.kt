package io.github.apostolichiro.videodl

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Quality
import io.github.apostolichiro.videodl.ui.Actions
import io.github.apostolichiro.videodl.ui.AppRoot
import io.github.apostolichiro.videodl.ui.BrowserActions
import io.github.apostolichiro.videodl.ui.VideoDlTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var browser: Browser? = null

    private fun getOrCreateBrowser(): Browser = browser ?: Browser(this, ::onSiteDownload).also { browser = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (savedInstanceState == null) handleShare(intent)

        val actions = Actions(
            download = { url, q -> request(NewJob(url, q)) },
            forceDownload = { n -> request(n, force = true) },
            startUpdate = { r -> Updater.download(applicationContext, r) },
            installUpdate = { f -> Updater.install(this, f) },
            readClipboard = {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            },
            updateEngine = { Thread { App.updateEngine(applicationContext, 0) }.start() },
            open = { f ->
                try {
                    startActivity(Notifs.viewIntent(f))
                } catch (_: ActivityNotFoundException) {
                    Store.notice.value = "이 파일을 열 수 있는 앱이 없어요."
                } catch (_: Exception) {
                    Store.notice.value = "파일을 열지 못했어요. 지워졌을 수 있어요."
                }
            },
            share = { files ->
                try {
                    startActivity(Notifs.shareIntent(files))
                } catch (_: Exception) {
                    Store.notice.value = "공유하지 못했어요."
                }
            },
            retry = { id ->
                Store.retry(id)
                DownloadService.start(this)
            },
            copy = { text ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("기록", text))
                Store.notice.value = "복사했어요."
            },
        )
        val browserActions = BrowserActions(
            download = ::downloadCurrentPage,
            clearLogins = {
                Cookies.clearAll()
                Store.notice.value = "브라우저 로그인을 모두 지웠어요."
            },
            leave = { Store.tab.value = 0 },
        )
        setContent { VideoDlTheme { AppRoot(actions, { getOrCreateBrowser() }, browserActions) } }

        // Pause the page (videos, timers) while the download list is showing.
        lifecycleScope.launch {
            Store.tab.collect { t -> browser?.webView?.let { if (t == 1) it.onResume() else it.onPause() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onPause() {
        super.onPause()
        browser?.webView?.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        if (Store.tab.value == 1) browser?.webView?.onResume()
        Thread { Updater.check(applicationContext, 3 * 60 * 60 * 1000L) }.start()
    }

    override fun onDestroy() {
        browser?.destroy()
        browser = null
        super.onDestroy()
    }

    /** A link shared from another app starts downloading right away, with the last-used quality. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        Store.tab.value = 0
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
        val url = Links.extract(text)
        if (url == null) {
            Store.notice.value = "공유한 내용에서 링크를 찾지 못했어요."
            return
        }
        request(NewJob(url, Store.quality))
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && !Store.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Store.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Adds a download, unless the same video was already downloaded: then asks first
     * ([Store.dupAsk] shows "보기 / 그래도 받기"). Returns true when it started.
     */
    private fun request(n: NewJob, force: Boolean = false, startedNotice: String? = null): Boolean {
        askNotificationsOnce()
        if (!force) {
            Downloads.alreadyHave(this, n.url, n.quality)?.let {
                Store.dupAsk.value = DupAsk(it, n)
                return false
            }
        }
        Downloads.start(this, n)
        startedNotice?.let { Store.notice.value = it }
        return true
    }

    /** The browser's [이 영상 받기] button. */
    private fun downloadCurrentPage() {
        val b = browser ?: return
        val page = b.webView.url ?: b.url.value
        if (!page.startsWith("http")) {
            Store.notice.value = "먼저 영상이 있는 페이지를 열어 주세요."
            return
        }
        request(
            NewJob(
                url = page,
                quality = Store.quality,
                origin = Origin.BROWSER,
                referer = page,
                pageTitle = b.title.value.ifBlank { null },
                userAgent = b.userAgent,
                fallbacks = b.found.value,
            ),
            startedNotice = "받기 시작했어요. [받기] 탭에서 볼 수 있어요.",
        )
    }

    /** A download button/link on a web page. Videos go through the engine, other files to Android's downloader. */
    private fun onSiteDownload(url: String, userAgent: String, disposition: String?, mime: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Store.notice.value = "이 사이트의 다운로드 방식은 아직 지원하지 않아요. [이 영상 받기]를 눌러 보세요."
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mime)
        val type = mime ?: Saver.mimeOf(name)
        val page = browser?.webView?.url
        askNotificationsOnce()
        if (type.startsWith("video/") || type.startsWith("audio/") || Saver.mimeOf(name).let { it.startsWith("video/") || it.startsWith("audio/") }) {
            request(
                NewJob(url = url, quality = Quality.BEST, origin = Origin.SITE, referer = page, pageTitle = name, userAgent = userAgent),
                startedNotice = "받기 시작했어요: $name",
            )
            return
        }
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setMimeType(type)
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${Saver.FOLDER}/$name")
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            page?.let { req.addRequestHeader("Referer", it) }
            getSystemService(DownloadManager::class.java).enqueue(req)
            Store.notice.value = "다운로드 폴더에 받는 중: $name"
        } catch (e: Exception) {
            Store.notice.value = "이 파일은 받지 못했어요: ${e.message}"
        }
    }
}
