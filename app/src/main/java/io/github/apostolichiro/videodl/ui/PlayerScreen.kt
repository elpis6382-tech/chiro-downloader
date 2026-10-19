package io.github.apostolichiro.videodl.ui

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.SurfaceView
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

private enum class Swipe { BRIGHTNESS, VOLUME }

@Composable
fun PlayerScreen(player: ExoPlayer, window: Window, onBack: () -> Unit, onRotate: () -> Unit, onOpenWith: (Int) -> Unit) {
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var aspect by remember { mutableFloatStateOf(0f) }
    var hasVideo by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf(player.mediaMetadata.title?.toString() ?: "") }
    var index by remember { mutableIntStateOf(player.currentMediaItemIndex) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var indicator by remember { mutableStateOf<Pair<ImageVector?, String>?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    val count = player.mediaItemCount

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    hasVideo = true
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                title = mediaMetadata.title?.toString() ?: title
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex
                title = mediaItem?.mediaMetadata?.title?.toString() ?: title
                aspect = 0f
                error = null
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                hasVideo = tracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) controls = true
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "이 파일은 여기서 재생할 수 없어요."
                controls = true
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(250)
        }
    }
    // Controls fade away after a few seconds of playing.
    LaunchedEffect(controls, playing, touched) {
        if (controls && playing) {
            delay(3200)
            controls = false
        }
    }
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(900)
            indicator = null
        }
    }
    BackHandler { onBack() }

    fun seekBy(ms: Long) {
        val target = (player.currentPosition + ms).coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(target)
        indicator = null to (if (ms > 0) "앞으로 ${ms / 1000}초" else "뒤로 ${-ms / 1000}초")
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c -> SurfaceView(c).also { player.setVideoSurfaceView(it) } },
            onRelease = { player.clearVideoSurfaceView(it) },
            modifier = Modifier.align(Alignment.Center).then(if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize()),
        )
        if (!hasVideo) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(120.dp).clip(CircleShape).background(LocalAccents.current.fab), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Music, null, tint = Color.White, modifier = Modifier.size(56.dp))
                }
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp, start = 32.dp, end = 32.dp))
            }
        }

        // Touch layer: tap = controls, double tap = ±10 s, swipe up/down = brightness (left) / volume (right).
        var swipe by remember { mutableStateOf(Swipe.VOLUME) }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controls = !controls; touched++ },
                        onDoubleTap = { o -> seekBy(if (o.x < size.width / 2) -10_000 else 10_000) },
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            swipe = if (o.x < size.width / 2) Swipe.BRIGHTNESS else Swipe.VOLUME
                            level = if (swipe == Swipe.BRIGHTNESS) currentBrightness(window, ctx) else {
                                audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            }
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            level = (level - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                            if (swipe == Swipe.BRIGHTNESS) {
                                window.attributes = window.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                                indicator = AppIcons.Brightness to "${(level * 100).toInt()}%"
                            } else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                                indicator = AppIcons.Volume to "${(level * 100).toInt()}%"
                            }
                        },
                    )
                },
        )

        indicator?.let { (icon, text) ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { level }, color = Color.White, trackColor = Color.White.copy(alpha = 0.25f), modifier = Modifier.width(110.dp), drawStopIndicator = {})
                    Spacer(Modifier.height(6.dp))
                }
                Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        AnimatedVisibility(visible = controls || error != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Top: back, title, speed, rotate.
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .safeDrawingPadding().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "닫기", onBack, tint = Color.White, size = 44.dp)
                    Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
                    TextButton(onClick = {
                        speed = when (speed) {
                            1f -> 1.25f
                            1.25f -> 1.5f
                            1.5f -> 2f
                            2f -> 0.5f
                            0.5f -> 0.75f
                            else -> 1f
                        }
                        player.setPlaybackSpeed(speed)
                        touched++
                    }) { Text(if (speed == 1f) "1x" else "${speed}x", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    if (hasVideo) RoundIcon(AppIcons.Fullscreen, "화면 돌리기", { onRotate(); touched++ }, tint = Color.White, size = 44.dp)
                }

                // Middle: previous, play/pause, next.
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    if (count > 1) {
                        RoundIcon(AppIcons.SkipPrevious, "이전", { player.seekToPreviousMediaItem(); touched++ }, tint = Color.White, size = 52.dp, iconSize = 30.dp, enabled = player.hasPreviousMediaItem())
                    }
                    Box(
                        Modifier.size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                            .clickable {
                                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                                if (player.isPlaying) player.pause() else player.play()
                                touched++
                            }
                            .semantics { contentDescription = if (playing) "일시정지" else "재생" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (playing) AppIcons.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    if (count > 1) {
                        RoundIcon(AppIcons.SkipNext, "다음", { player.seekToNextMediaItem(); touched++ }, tint = Color.White, size = 52.dp, iconSize = 30.dp, enabled = player.hasNextMediaItem())
                    }
                }

                error?.let { e ->
                    Column(Modifier.align(Alignment.Center).padding(top = 150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(e, color = Color.White, fontSize = 14.sp)
                        TextButton(onClick = { onOpenWith(index) }) { Text("다른 앱으로 열기", color = Color.White) }
                    }
                }

                // Bottom: time and seek bar.
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .safeDrawingPadding().padding(start = 16.dp, end = 16.dp, bottom = 10.dp, top = 24.dp),
                ) {
                    val shown = dragging?.toLong() ?: position
                    Row(Modifier.fillMaxWidth()) {
                        Text(durationText(shown / 1000), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(if (duration > 0) durationText(duration / 1000) else "--:--", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                    }
                    Slider(
                        value = (dragging ?: position.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                        onValueChange = { dragging = it; touched++ },
                        onValueChangeFinished = {
                            dragging?.let { player.seekTo(it.toLong()) }
                            dragging = null
                        },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White, activeTrackColor = Color(0xFF6FB4FF), inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "재생 위치" },
                    )
                }
            }
        }
    }
}

private fun currentBrightness(window: Window, ctx: Context): Float {
    val b = window.attributes.screenBrightness
    if (b >= 0f) return b
    return try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
    } catch (_: Exception) {
        0.5f
    }
}
