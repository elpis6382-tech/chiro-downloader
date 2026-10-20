package io.github.apostolichiro.videodl

import android.Manifest
import android.app.DownloadManager
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Quality
import io.github.apostolichiro.videodl.ui.AppActions
import io.github.apostolichiro.videodl.ui.AppRoot
import io.github.apostolichiro.videodl.ui.VideoDlTheme
import io.github.apostolichiro.videodl.ui.openSheet
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity(), FullscreenHost {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private lateinit var tabs: Tabs

    /** Android asks the person before deleting a file this app didn't make (e.g. after reinstalling). */
    private var pendingDelete: Pair<Job, SavedFile>? = null
    private val askDelete = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val p = pendingDelete ?: return@registerForActivityResult
        pendingDelete = null
        if (r.resultCode == RESULT_OK) {
            // On Android 10 the permission is given; the delete itself still has to be done.
            if (Build.VERSION.SDK_INT < 30) runCatching { contentResolver.delete(Uri.parse(p.second.uri), null, null) }
            Store.removeFile(p.first.id, p.second.uri)
            Store.notice.value = "지웠어요."
        }
    }

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private val leaveFullscreen = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hideCustomView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        tabs = Tabs(this, lifecycleScope, this, ::onSiteDownload)
        if (savedInstanceState == null) handleIntent(intent)

        val app = AppActions(
            download = { n -> start(n) },
            play = ::play,
            openWith = { f ->
                try {
                    startActivity(Intent.createChooser(Notifs.viewIntent(f), "다른 앱으로 열기"))
                } catch (_: Exception) {
                    Store.notice.value = "이 파일을 열 수 있는 앱이 없어요."
                }
            },
            share = { files ->
                try {
                    startActivity(Notifs.shareIntent(files))
                } catch (_: Exception) {
                    Store.notice.value = "공유하지 못했어요."
                }
            },
            delete = ::deleteFile,
            rename = ::renameFile,
            openPage = { url ->
                Store.tab.value = Store.TAB_HOME
                val t = tabs.current.value
                if (t.isHome) tabs.open(url, t) else tabs.newTab(url)
            },
            pasteLink = {
                val link = Links.extract(readClipboard())
                if (link != null) openSheet(SheetRequest(link)) else Store.notice.value = "복사한 링크가 없어요. 영상 링크를 복사한 뒤 눌러 주세요."
            },
            pause = { id -> DownloadService.pause(id) },
            resume = { id ->
                Store.resume(id)
                DownloadService.start(this)
            },
            cancel = ::cancel,
            retry = { id ->
                Store.retry(id)
                DownloadService.start(this)
            },
            copy = { text ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("기록", text))
                Store.notice.value = "복사했어요."
            },
            shareText = { text ->
                runCatching {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "링크 공유"))
                }
            },
            openExternal = { url ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    Store.notice.value = "열 수 있는 브라우저가 없어요."
                }
            },
            startUpdate = { r -> Updater.download(applicationContext, r) },
            installUpdate = { f -> Updater.install(this, f) },
            checkUpdate = {
                Store.notice.value = "새 버전을 확인하고 있어요…"
                Thread {
                    Updater.check(applicationContext, 0)
                    if (Store.update.value is UpdateState.None) Store.notice.value = "지금이 최신 버전이에요."
                }.start()
            },
            updateEngine = { Thread { App.updateEngine(applicationContext, 0) }.start() },
            clearLogins = {
                Cookies.clearAll()
                Store.notice.value = "사이트 로그인을 모두 지웠어요."
            },
        )
        setContent { VideoDlTheme { AppRoot(app, tabs) } }
        onBackPressedDispatcher.addCallback(this, leaveFullscreen)

        // Web pages only run while the browser is on screen.
        lifecycleScope.launch {
            Store.tab.collect { t -> if (t == Store.TAB_HOME) tabs.resumeCurrent() else tabs.pauseAll() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        tabs.pauseAll()
        tabs.save()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        if (Store.tab.value == Store.TAB_HOME) tabs.resumeCurrent()
        Thread { Updater.check(applicationContext, 3 * 60 * 60 * 1000L) }.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets an app read the clipboard while it has focus.
        if (hasFocus) checkClipboard()
    }

    override fun onDestroy() {
        tabs.destroy()
        super.onDestroy()
    }

    /** A link copied in another app is offered on the home page (once per copy). */
    private fun checkClipboard() {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            val desc = cm.primaryClipDescription ?: return
            val stamp = desc.timestamp
            if (stamp == Store.clipSeenAt) return
            Store.clipSeenAt = stamp
            val link = Links.extract(readClipboard()) ?: return
            if (Downloads.duplicateOf(this, link, Store.quality) == null) {
                Store.clipLink.value = link
                // Look it up right away, so the sheet is ready by the time [받기] is pressed.
                lifecycleScope.launch {
                    Infos.fetch(applicationContext, link)
                    QuickMeta.fetch(link)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readClipboard(): String? = try {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (_: Exception) {
        null
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val tab = intent.getIntExtra(EXTRA_TAB, -1)
        if (tab >= 0) Store.tab.value = tab
        if (intent.action != Intent.ACTION_SEND) return
        // A link shared from another app (the very first time, when the app has to ask for notifications).
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
        val url = Links.extract(text)
        if (url == null) {
            Store.notice.value = "공유한 내용에서 링크를 찾지 못했어요."
            return
        }
        Store.tab.value = Store.TAB_PROGRESS
        start(NewJob(url, Store.quality))
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && !Store.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Store.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Every download the app starts comes through here; the same video is never downloaded twice. */
    private fun start(n: NewJob) {
        Downloads.duplicateOf(this, n)?.let {
            Store.notice.value = Downloads.duplicateNotice(it)
            return
        }
        askNotificationsOnce()
        Downloads.start(this, n)
        Store.notice.value = "다운로드를 시작했어요. [받는 중]에서 볼 수 있어요."
    }

    /** Videos and songs open in the app's own player (with the others from the list as next/previous). */
    private fun play(job: Job, file: SavedFile) {
        if (!file.isVideo && !file.isAudio) {
            try {
                startActivity(Notifs.viewIntent(file))
            } catch (_: Exception) {
                Store.notice.value = "이 파일을 열 수 있는 앱이 없어요."
            }
            return
        }
        val all = Store.jobs.value.filter { it.status == Status.DONE }.flatMap { it.files }.filter { it.isVideo || it.isAudio }
        val i = all.indexOfFirst { it.uri == file.uri }
        val list = if (i >= 0) all else listOf(file)
        startActivity(PlayerActivity.intent(this, list, i.coerceAtLeast(0)))
    }

    /** Stops a download and takes it off the list (its kept pieces too). */
    private fun cancel(id: String) {
        val job = Store.get(id) ?: return
        if (job.status.active) {
            Store.removeAfterStop += id
            DownloadService.cancel(id)
        } else {
            if (job.status == Status.QUEUED || job.status == Status.PAUSED) DownloadService.cancel(id)
            Store.remove(id)
            File(cacheDir, "dl/$id").deleteRecursively()
        }
    }

    private fun deleteFile(job: Job, file: SavedFile) {
        val uri = Uri.parse(file.uri)
        try {
            contentResolver.delete(uri, null, null)
            Store.removeFile(job.id, file.uri)
            Store.notice.value = "지웠어요."
        } catch (e: SecurityException) {
            val sender = when {
                Build.VERSION.SDK_INT >= 30 -> MediaStore.createDeleteRequest(contentResolver, listOf(uri)).intentSender
                e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender == null) {
                Store.notice.value = "이 파일은 지울 수 없어요. 갤러리에서 지워 주세요."
                return
            }
            pendingDelete = job to file
            askDelete.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            Store.notice.value = "지우지 못했어요: ${e.message}"
        }
    }

    private fun renameFile(job: Job, file: SavedFile, name: String) {
        try {
            val n = contentResolver.update(Uri.parse(file.uri), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)
            if (n > 0) {
                Store.renameFile(job.id, file.uri, name)
                Store.notice.value = "이름을 바꿨어요."
            } else {
                Store.notice.value = "이름을 바꾸지 못했어요."
            }
        } catch (_: Exception) {
            Store.notice.value = "이 파일은 이름을 바꿀 수 없어요."
        }
    }

    /** A download button/link on a web page. Videos go through the engine, other files to Android's downloader. */
    private fun onSiteDownload(url: String, userAgent: String, disposition: String?, mime: String?, page: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            Store.notice.value = "이 사이트의 다운로드 방식은 아직 지원하지 않아요. 파란 다운로드 버튼을 눌러 보세요."
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mime)
        val type = mime ?: Saver.mimeOf(name)
        askNotificationsOnce()
        if (type.startsWith("video/") || type.startsWith("audio/") || Saver.mimeOf(name).let { it.startsWith("video/") || it.startsWith("audio/") }) {
            start(NewJob(url = url, quality = Quality.BEST, origin = Origin.SITE, referer = page, pageTitle = name, userAgent = userAgent, title = name.substringBeforeLast('.')))
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

    // Full-screen video from a web page.
    override fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        view.setBackgroundColor(Color.BLACK)
        (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        // Added last so [뒤로] leaves full screen before the browser goes back a page.
        leaveFullscreen.remove()
        onBackPressedDispatcher.addCallback(this, leaveFullscreen)
        leaveFullscreen.isEnabled = true
    }

    override fun hideCustomView() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        customView = null
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        leaveFullscreen.isEnabled = false
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
