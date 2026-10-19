package io.github.apostolichiro.videodl

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import io.github.apostolichiro.videodl.ui.PlayerScreen
import io.github.apostolichiro.videodl.ui.VideoDlTheme

/** The app's own player for downloaded videos and songs: full screen, swipe for brightness/volume. */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var items: List<SavedFile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = intent.getStringArrayListExtra(EXTRA_URIS) ?: arrayListOf()
        val names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        val mimes = intent.getStringArrayListExtra(EXTRA_MIMES) ?: arrayListOf()
        items = uris.indices.map { i -> SavedFile(uris[i], names.getOrElse(i) { "" }, mimes.getOrElse(i) { "video/mp4" }, 0) }
        if (items.isEmpty()) {
            finish()
            return
        }
        val start = (savedInstanceState?.getInt("index") ?: intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(0, items.size - 1)
        val position = savedInstanceState?.getLong("position") ?: 0L

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()

        val p = ExoPlayer.Builder(this).build()
        p.setMediaItems(
            items.map { f ->
                MediaItem.Builder().setUri(Uri.parse(f.uri))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(f.name.substringBeforeLast('.')).build())
                    .build()
            },
            start, position,
        )
        p.prepare()
        p.playWhenReady = true
        player = p

        setContent {
            VideoDlTheme {
                PlayerScreen(
                    player = p,
                    window = window,
                    onBack = { finish() },
                    onRotate = ::rotate,
                    onOpenWith = { i -> openWith(items[i]) },
                )
            }
        }
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    /** Turns the screen sideways (or back upright). */
    private fun rotate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun openWith(f: SavedFile) {
        try {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(f.uri), f.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "다른 앱으로 열기",
                ),
            )
        } catch (_: ActivityNotFoundException) {
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            outState.putInt("index", it.currentMediaItemIndex)
            outState.putLong("position", it.currentPosition)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_MIMES = "mimes"
        private const val EXTRA_INDEX = "index"

        /** Plays [files] (videos and songs) starting at [index]; the others are next/previous. */
        fun intent(ctx: Context, files: List<SavedFile>, index: Int): Intent =
            Intent(ctx, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(files.map { it.uri }))
                .putStringArrayListExtra(EXTRA_NAMES, ArrayList(files.map { it.name }))
                .putStringArrayListExtra(EXTRA_MIMES, ArrayList(files.map { it.mime }))
                .putExtra(EXTRA_INDEX, index)
    }
}
