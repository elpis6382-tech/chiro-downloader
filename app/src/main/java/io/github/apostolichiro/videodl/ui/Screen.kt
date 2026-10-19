package io.github.apostolichiro.videodl.ui

import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.apostolichiro.videodl.DownloadService
import io.github.apostolichiro.videodl.EngineState
import io.github.apostolichiro.videodl.Job
import io.github.apostolichiro.videodl.R
import io.github.apostolichiro.videodl.SavedFile
import io.github.apostolichiro.videodl.Saver
import io.github.apostolichiro.videodl.Status
import io.github.apostolichiro.videodl.Store
import io.github.apostolichiro.videodl.Texts
import io.github.apostolichiro.videodl.engine.Engine
import io.github.apostolichiro.videodl.engine.Links
import io.github.apostolichiro.videodl.engine.Quality
import androidx.compose.material3.CircularProgressIndicator
import io.github.apostolichiro.videodl.AppRelease
import io.github.apostolichiro.videodl.Downloads
import io.github.apostolichiro.videodl.NewJob
import io.github.apostolichiro.videodl.UpdateState
import io.github.apostolichiro.videodl.engine.Errors
import io.github.apostolichiro.videodl.engine.Estimate
import io.github.apostolichiro.videodl.engine.Fmt
import io.github.apostolichiro.videodl.engine.Preview
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Actions(
    val download: (String, Quality) -> Unit,
    val forceDownload: (NewJob) -> Unit,
    val startUpdate: (AppRelease) -> Unit,
    val installUpdate: (File) -> Unit,
    val readClipboard: () -> String?,
    val updateEngine: () -> Unit,
    val open: (SavedFile) -> Unit,
    val share: (List<SavedFile>) -> Unit,
    val retry: (String) -> Unit,
    val copy: (String) -> Unit,
)

@Composable
fun MainScreen(actions: Actions) {
    val jobs by Store.jobs.collectAsStateWithLifecycle()
    val engine by Store.engine.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var quality by remember { mutableStateOf(Store.quality) }
    var detailOf by remember { mutableStateOf<Job?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val update by Store.update.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current

    // Preview of the link in the box: title, picture, sizes per quality. Cancelled when the link changes.
    val link = Links.extract(text)
    var preview by remember { mutableStateOf<PreviewUi?>(null) }
    LaunchedEffect(link) {
        preview = null
        if (link == null) return@LaunchedEffect
        delay(400)
        preview = PreviewUi.Loading
        val pid = "info-" + System.nanoTime()
        val result = try {
            coroutineScope {
                val work = async(Dispatchers.IO) { Engine.info(ctx, link, pid) }
                try {
                    PreviewUi.Ready(work.await())
                } catch (e: CancellationException) {
                    Engine.cancel(pid)
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PreviewUi.Failed(Errors.korean(Errors.lastErrorLine(e.message) ?: e.message))
        }
        // The link may have changed (or been cleared) while this was loading: don't show an old result.
        if (isActive) preview = result
    }
    val doneCount = jobs.count { it.status == Status.DONE }
    val already by produceState<Job?>(null, link, quality, doneCount) {
        value = link?.let { withContext(Dispatchers.IO) { Downloads.alreadyHave(ctx, it, quality) } }
    }

    val shown = if (query.isBlank()) jobs else jobs.filter { j ->
        (listOf(Texts.title(j), j.url) + j.files.map { it.name }).any { it.contains(query.trim(), ignoreCase = true) }
    }

    fun submit() {
        val url = Links.extract(text) ?: return
        actions.download(url, quality)
        text = ""
        keyboard?.hide()
    }

    run {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") { Header() }
            if (update !is UpdateState.None) item(key = "update") { UpdateCard(update, actions) }
            item(key = "input") {
                InputCard(
                    text = text,
                    onText = { text = it },
                    preview = preview,
                    already = already,
                    quality = quality,
                    onQuality = { quality = it; Store.quality = it },
                    onPaste = {
                        val clip = actions.readClipboard()
                        val link = Links.extract(clip)
                        if (link != null) text = link else Store.notice.value = "복사한 내용에 링크가 없어요."
                    },
                    onSubmit = { submit() },
                )
            }
            if (jobs.isEmpty()) {
                item(key = "empty") { HowTo() }
            } else {
                item(key = "listhead") {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("받은 목록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (jobs.any { it.status == Status.DONE }) {
                                TextButton(onClick = { Store.clearFinished() }) { Text("완료된 것 지우기") }
                            }
                        }
                        if (jobs.size >= 3) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("받은 목록 검색 (제목, 사이트)", maxLines = 1) },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        Box(
                                            Modifier.size(40.dp).clip(CircleShape).clickable { query = "" },
                                            contentAlignment = Alignment.Center,
                                        ) { CrossGlyph(12.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                },
                            )
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "nomatch") {
                        Text(
                            "'${query.trim()}'에 맞는 영상이 없어요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            items(shown, key = { it.id }) { job ->
                JobCard(job, actions, onDetail = { detailOf = it })
            }
            item(key = "engine") { EngineCard(engine, actions.updateEngine) }
        }
    }

    detailOf?.let { job ->
        val detail = job.detail ?: ""
        AlertDialog(
            onDismissRequest = { detailOf = null },
            confirmButton = { TextButton(onClick = { detailOf = null }) { Text("닫기") } },
            dismissButton = { TextButton(onClick = { actions.copy(job.url + "\n\n" + detail) }) { Text("복사") } },
            title = { Text("자세한 기록") },
            text = {
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(detail.ifBlank { "기록이 없어요." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            },
        )
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text("영상 다운로더", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(
            "링크만 넣으면 MP4로 받아서 다운로드 폴더에 저장해요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InputCard(
    text: String,
    onText: (String) -> Unit,
    preview: PreviewUi?,
    already: Job?,
    quality: Quality,
    onQuality: (Quality) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("영상 링크", maxLines = 1) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                trailingIcon = {
                    if (text.isEmpty()) {
                        TextButton(onClick = onPaste) { Text("붙여넣기") }
                    } else {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable { onText("") },
                            contentAlignment = Alignment.Center,
                        ) { CrossGlyph(12.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
            )
            if (preview != null || already != null) {
                Spacer(Modifier.height(12.dp))
                PreviewBox(preview, already)
            }
            Spacer(Modifier.height(12.dp))
            val p = (preview as? PreviewUi.Ready)?.preview
            // A hidden quality gives the same file as "최고 화질", so that button shows as chosen.
            val chosen = if (p != null && !p.shows(quality)) Quality.BEST else quality
            // Wraps onto a second line on narrow phones instead of hiding options off-screen.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.entries.filter { p == null || p.isPlaylist || p.shows(it) }.forEach { q ->
                    val c = p?.choices?.get(q)
                    val sub = c?.let {
                        listOfNotNull(
                            if (q == Quality.BEST) Estimate.resLabel(it.res) else null,
                            it.bytes?.let { b -> "약 " + Fmt.size(b) },
                        ).joinToString(" · ")
                    }?.ifEmpty { null }
                    FilterChip(
                        selected = q == chosen,
                        onClick = { onQuality(q) },
                        label = {
                            Column(Modifier.padding(vertical = if (sub != null) 4.dp else 0.dp)) {
                                Text(q.label)
                                if (sub != null) Text(sub, fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSubmit,
                enabled = Links.looksLikeLink(text),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("받기", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun HowTo() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("이렇게 쓰면 제일 편해요", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Step("1", "유튜브·인스타·틱톡·X 같은 앱에서 영상의 [공유]를 누르고")
            Step("2", "앱 목록에서 [영상 다운로더]를 고르면 바로 받기 시작해요.")
            Step("3", "다 받은 영상은 다운로드 폴더 안의 Chiro video 폴더(갤러리 앨범)에 있어요.")
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Text(n, color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun JobCard(job: Job, actions: Actions, onDetail: (Job) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Thumb(job)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        Texts.title(job),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(top = 2.dp, end = 4.dp),
                    )
                    if (job.status.finished) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).clickable { Store.remove(job.id) },
                            contentAlignment = Alignment.Center,
                        ) { CrossGlyph(10.dp, colors.onSurfaceVariant) }
                    }
                }
                Text(
                    "${Links.host(job.url)} · ${job.quality.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    Texts.status(job),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        job.status == Status.FAILED -> colors.error
                        job.status == Status.DONE && job.error == null -> colors.tertiary
                        job.status == Status.DONE -> colors.error
                        else -> colors.onSurfaceVariant
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
                if (job.status == Status.DONE && job.error != null) {
                    Text(job.error, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(end = 8.dp))
                }
                if (job.status.active) {
                    Spacer(Modifier.height(8.dp))
                    val p = job.progress
                    if (p != null && job.status == Status.DOWNLOADING) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                    }
                }
                Row {
                    when {
                        job.status == Status.QUEUED || job.status.active -> {
                            TextButton(onClick = { DownloadService.cancel(job.id) }) { Text("취소") }
                        }
                        job.status == Status.DONE -> {
                            if (job.files.size == 1) TextButton(onClick = { actions.open(job.files[0]) }) { Text("보기") }
                            TextButton(onClick = { actions.share(job.files) }) { Text("공유") }
                            if (job.error != null && job.detail != null) TextButton(onClick = { onDetail(job) }) { Text("자세히") }
                        }
                        else -> {
                            TextButton(onClick = { actions.retry(job.id) }) { Text("다시") }
                            if (job.detail != null) TextButton(onClick = { onDetail(job) }) { Text("자세히") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(job: Job) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val file = job.files.firstOrNull()
    val bitmap by produceState<ImageBitmap?>(null, file?.uri) {
        value = if (file != null && (file.mime.startsWith("video/") || file.mime.startsWith("image/"))) {
            withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.loadThumbnail(Uri.parse(file.uri), Size(320, 320), null).asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        } else null
    }
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        when {
            b != null -> Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            file?.mime?.startsWith("audio/") == true -> Text("♪", fontSize = 26.sp, color = colors.primary)
            job.status == Status.FAILED -> Text("!", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = colors.error)
            job.status == Status.CANCELLED -> CrossGlyph(16.dp, colors.onSurfaceVariant)
            else -> Icon(painterResource(R.drawable.ic_stat), contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun EngineCard(engine: EngineState, onUpdate: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 4.dp)) {
            Text("다운로드 엔진", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val line = when (engine) {
                EngineState.Preparing -> "처음 실행 준비 중… (한 번만 해요)"
                EngineState.Updating -> "새 버전 확인 중…"
                is EngineState.Broken -> engine.message
                is EngineState.Ready -> buildString {
                    append("yt-dlp ")
                    append(Engine.shortVersion(engine.version) ?: "기본 버전")
                    when {
                        engine.updateFailed -> append(" · 새 버전 확인 실패 (인터넷 연결 확인)")
                        engine.checkedAt > 0 -> append(" · ${ago(engine.checkedAt)} 확인")
                    }
                }
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(
                "저장 위치: 내장 저장공간 › Download › ${Saver.FOLDER}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            TextButton(
                onClick = onUpdate,
                enabled = engine is EngineState.Ready || engine is EngineState.Broken,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) { Text(if (engine is EngineState.Updating) "확인 중…" else "엔진 업데이트 확인") }
        }
    }
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "방금"
        m < 60 -> "${m}분 전"
        m < 60 * 24 -> "${m / 60}시간 전"
        else -> "${m / (60 * 24)}일 전"
    }
}

@Composable
private fun CrossGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = 2.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(this.size.width, this.size.height), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(this.size.width, 0f), Offset(0f, this.size.height), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/** What the link box knows about the pasted link. */
sealed interface PreviewUi {
    data object Loading : PreviewUi
    data class Ready(val preview: Preview) : PreviewUi
    data class Failed(val message: String) : PreviewUi
}

@Composable
private fun PreviewBox(preview: PreviewUi?, already: Job?) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (preview) {
            PreviewUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("영상 정보 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            is PreviewUi.Failed -> Text(
                "미리보기를 못 불러왔어요. 그래도 [받기]는 해볼 수 있어요.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            is PreviewUi.Ready -> {
                val p = preview.preview
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        val pic = netImage(p.thumbnail)
                        if (pic != null) Image(pic, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(painterResource(R.drawable.ic_stat), contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.title ?: "제목 없음", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            p.uploader,
                            p.duration?.let { durationText(it) },
                            p.playlistCount?.let { "재생목록 · 영상 ${it}개" },
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (p.isPlaylist) {
                    Text("[받기]를 누르면 이 목록의 영상을 모두 받아요.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            null -> Unit
        }
        if (already != null) {
            Text(
                "이미 받은 영상이에요 (${Texts.status(already)})",
                style = MaterialTheme.typography.bodySmall, color = colors.tertiary, fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun durationText(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A picture from the web (a video's thumbnail), loaded off the main thread. */
@Composable
private fun netImage(url: String?): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, url) {
        value = if (url == null) null else withContext(Dispatchers.IO) {
            try {
                val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) video-downloader")
                }
                c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    return bmp
}

@Composable
private fun UpdateCard(state: UpdateState, actions: Actions) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp)) {
            Text("새 버전이 나왔어요", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            when (state) {
                is UpdateState.Available -> {
                    Text("버튼 한 번이면 받고 설치까지 돼요.", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
                    TextButton(onClick = { actions.startUpdate(state.release) }, contentPadding = PaddingValues(0.dp)) { Text("지금 업데이트") }
                }
                is UpdateState.Downloading -> {
                    val f = state.fraction
                    Text("새 버전 받는 중" + (f?.let { " ${(it * 100).toInt()}%" } ?: "…"), style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
                    Spacer(Modifier.height(8.dp))
                    if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                    Spacer(Modifier.height(10.dp))
                }
                is UpdateState.Ready -> {
                    Text("다 받았어요. [설치하기]를 누르고 설치 화면에서 [업데이트]를 눌러 주세요.", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
                    TextButton(onClick = { actions.installUpdate(state.file) }, contentPadding = PaddingValues(0.dp)) { Text("설치하기") }
                }
                is UpdateState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = colors.error)
                    TextButton(onClick = { actions.startUpdate(state.release) }, contentPadding = PaddingValues(0.dp)) { Text("다시") }
                }
                UpdateState.None -> Unit
            }
        }
    }
}
