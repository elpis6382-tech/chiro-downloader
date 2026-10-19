package io.github.apostolichiro.videodl.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.apostolichiro.videodl.SavedFile
import io.github.apostolichiro.videodl.engine.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/** A round, quiet icon button with a name for screen readers (and the UI tests). */
@Composable
fun RoundIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Box(
        modifier.size(size).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(iconSize))
    }
}

/** Number of open tabs in a rounded square, like other browsers. */
@Composable
fun TabCount(count: Int, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = "탭 ${count}개" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(21.dp).border(1.8.dp, c, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 99) "∞" else "$count", fontSize = if (count > 9) 9.sp else 11.sp, fontWeight = FontWeight.Bold, color = c)
        }
    }
}

/** A picture from the web (a video's thumbnail), loaded off the main thread and kept in memory for a while. */
@Composable
fun netImage(url: String?): ImageBitmap? {
    val bmp by produceState(Images.memory[url ?: ""], url) {
        if (url == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { Images.load(url) }
    }
    return bmp
}

/** The phone's own thumbnail of a downloaded file. */
@Composable
fun fileThumb(file: SavedFile?): ImageBitmap? {
    val ctx = LocalContext.current
    val key = "file:" + (file?.uri ?: "")
    val bmp by produceState(Images.memory[key], file?.uri) {
        if (file == null || value != null || !(file.isVideo || file.mime.startsWith("image/"))) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.loadThumbnail(Uri.parse(file.uri), Size(480, 270), null).asImageBitmap().also { Images.memory[key] = it }
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

/** Length of a downloaded video or song in seconds (null when unknown). */
@Composable
fun fileDuration(file: SavedFile): Long? {
    val ctx = LocalContext.current
    val d by produceState(Images.durations[file.uri], file.uri) {
        if (value != null || !(file.isVideo || file.isAudio)) return@produceState
        value = withContext(Dispatchers.IO) { Images.duration(ctx, file.uri) }
    }
    return d
}

object Images {
    val memory = ConcurrentHashMap<String, ImageBitmap>()
    val durations = ConcurrentHashMap<String, Long>()

    fun load(url: String): ImageBitmap? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) video-downloader")
        }
        val b = c.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        b?.also { memory[url] = it }
    } catch (_: Exception) {
        null
    }

    fun duration(ctx: Context, uri: String): Long? {
        try {
            ctx.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.DURATION), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    val ms = c.getLong(0)
                    if (ms > 0) return (ms / 1000).also { durations[uri] = it }
                }
            }
        } catch (_: Exception) {
        }
        return try {
            val r = android.media.MediaMetadataRetriever()
            try {
                ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                    r.setDataSource(pfd.fileDescriptor)
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000 }
                }?.also { durations[uri] = it }
            } finally {
                r.release()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** A site's own icon (cached on the phone), for the home page shortcuts and tabs. */
    fun favicon(ctx: Context, link: String): ImageBitmap? {
        val host = Links.host(link).lowercase()
        if (host.isBlank()) return null
        val key = "icon:$host"
        memory[key]?.let { return it }
        val dir = File(ctx.filesDir, "icons").apply { mkdirs() }
        val file = File(dir, host.replace(Regex("[^a-z0-9.-]"), "_") + ".png")
        if (file.isFile && file.length() > 0) {
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.let { memory[key] = it; return it }
        }
        return try {
            val c = (URL("https://www.google.com/s2/favicons?domain=$host&sz=128").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
            }
            val bytes = c.inputStream.use { it.readBytes() }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // The service sends a 16px globe when it knows no icon; a letter looks better than that.
            if (bmp.width < 32) return null
            file.writeBytes(bytes)
            bmp.asImageBitmap().also { memory[key] = it }
        } catch (_: Exception) {
            null
        }
    }
}

/** A site's icon in a soft circle, or its first letter while the icon is loading / missing. */
@Composable
fun SiteIcon(name: String, url: String, size: Dp = 52.dp) {
    val ctx = LocalContext.current
    val icon by produceState(Images.memory["icon:" + Links.host(url).lowercase()], url) {
        if (value == null) value = withContext(Dispatchers.IO) { Images.favicon(ctx, url) }
    }
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val i = icon
        if (i != null) {
            Image(i, contentDescription = null, modifier = Modifier.size(size * 0.5f).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
        } else {
            Text(name.take(1).uppercase(), fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Bold, color = colors.primary)
        }
    }
}

/** A thumbnail box with an optional length label in the corner. */
@Composable
fun ThumbBox(picture: ImageBitmap?, width: Dp, height: Dp, placeholder: ImageVector, corner: String? = null, dim: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(width, height).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant)) {
        if (picture != null) {
            Image(picture, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = if (dim) 0.45f else 1f)
        } else {
            Icon(placeholder, contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center).size(26.dp))
        }
        if (corner != null) {
            Text(
                corner,
                color = Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** A screen's title row. */
@Composable
fun ScreenTitle(text: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

/** An empty screen: what it's for and what to do. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) Box(Modifier.padding(top = 18.dp)) { action() }
    }
}

fun durationText(sec: Long): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, (sec % 3600) / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분 전"
        m < 60 * 24 -> "${m / 60}시간 전"
        else -> "${m / (60 * 24)}일 전"
    }
}

/** "오늘 14:03", "어제", "10월 3일", "2025년 12월 1일" */
fun dayText(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val sameYear = now.get(Calendar.YEAR) == c.get(Calendar.YEAR)
    val days = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && days == 0 -> "오늘 %02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        sameYear && days == 1 -> "어제"
        sameYear -> "${c.get(Calendar.MONTH) + 1}월 ${c.get(Calendar.DAY_OF_MONTH)}일"
        else -> "${c.get(Calendar.YEAR)}년 ${c.get(Calendar.MONTH) + 1}월 ${c.get(Calendar.DAY_OF_MONTH)}일"
    }
}
