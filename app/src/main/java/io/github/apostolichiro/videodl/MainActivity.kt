package io.github.apostolichiro.videodl

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Quality
import io.github.apostolichiro.videodl.ui.Actions
import io.github.apostolichiro.videodl.ui.MainScreen
import io.github.apostolichiro.videodl.ui.VideoDlTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (savedInstanceState == null) handleShare(intent)

        val actions = Actions(
            download = { url, q -> start(url, q) },
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
        setContent { VideoDlTheme { MainScreen(actions) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** A link shared from another app starts downloading right away, with the last-used quality. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
        val url = Links.extract(text)
        if (url == null) {
            Store.notice.value = "공유한 내용에서 링크를 찾지 못했어요."
            return
        }
        start(url, Store.quality)
    }

    private fun start(url: String, quality: Quality) {
        if (Build.VERSION.SDK_INT >= 33 && !Store.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Store.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Store.add(url, quality)
        DownloadService.start(this)
    }
}
