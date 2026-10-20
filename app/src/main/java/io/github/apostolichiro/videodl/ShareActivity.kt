package io.github.apostolichiro.videodl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import io.github.apostolichiro.videodl.engine.Links

/**
 * "Share → 영상 다운로더" from another app: starts the download without opening the app,
 * so the person stays where they were. Progress shows in the notification.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (savedInstanceState == null) handle(intent)
        finish()
    }

    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    private fun handle(intent: Intent?) {
        val text = listOfNotNull(intent?.getStringExtra(Intent.EXTRA_TEXT), intent?.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
        val url = Links.extract(text)
        if (url == null) {
            toast("공유한 내용에서 링크를 찾지 못했어요.")
            return
        }
        // The very first time on Android 13+, open the app instead: it asks to show download notifications.
        if (Build.VERSION.SDK_INT >= 33 && !Store.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val quality = Store.quality
        Downloads.duplicateOf(this, url, quality)?.let {
            toast(Downloads.duplicateNotice(it))
            return
        }
        Downloads.start(this, NewJob(url, quality))
        toast("받기 시작했어요 · ${Links.host(url)} · ${quality.label}")
    }
}
