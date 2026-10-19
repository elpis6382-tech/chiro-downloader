package io.github.apostolichiro.videodl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.apostolichiro.videodl.Job
import io.github.apostolichiro.videodl.Status
import io.github.apostolichiro.videodl.Store
import io.github.apostolichiro.videodl.Texts
import io.github.apostolichiro.videodl.engine.Links

/** The 받는 중 tab: everything not finished yet, plus the ones that failed. */
@Composable
fun ProgressScreen(app: AppActions) {
    val jobs by Store.jobs.collectAsStateWithLifecycle()
    val list = jobs.filter { it.status != Status.DONE }
    val going = list.filter { it.status.active || it.status == Status.QUEUED }
    val paused = list.filter { it.status == Status.PAUSED }
    var detailOf by remember { mutableStateOf<Job?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("받는 중") {
            when {
                going.isNotEmpty() -> TextButton(onClick = { going.forEach { app.pause(it.id) } }) { Text("모두 일시정지") }
                paused.isNotEmpty() -> TextButton(onClick = { paused.forEach { app.resume(it.id) } }) { Text("모두 이어받기") }
            }
            val ended = list.filter { it.status == Status.FAILED || it.status == Status.CANCELLED }
            if (ended.isNotEmpty() && going.isEmpty() && paused.isEmpty()) {
                TextButton(onClick = { ended.forEach { Store.remove(it.id) } }) { Text("목록 비우기") }
            }
        }
        if (list.isEmpty()) {
            EmptyState(
                AppIcons.Downloading,
                "받고 있는 영상이 없어요",
                "사이트에서 영상을 열고 파란 버튼을 누르거나, 다른 앱에서 [공유] → 영상 다운로더를 고르면 여기에 나와요.",
            ) {
                Button(onClick = { Store.tab.value = Store.TAB_HOME }, shape = RoundedCornerShape(14.dp)) { Text("사이트 열기") }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.id }) { job -> JobRow(job, app, onDetail = { detailOf = it }) }
        }
    }

    detailOf?.let { job ->
        AlertDialog(
            onDismissRequest = { detailOf = null },
            confirmButton = { TextButton(onClick = { detailOf = null }) { Text("닫기") } },
            dismissButton = { TextButton(onClick = { app.copy(job.url + "\n\n" + (job.detail ?: "")) }) { Text("복사") } },
            title = { Text("자세한 기록") },
            text = {
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text((job.detail ?: "").ifBlank { "기록이 없어요." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            },
        )
    }
}

@Composable
private fun JobRow(job: Job, app: AppActions, onDetail: (Job) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val failed = job.status == Status.FAILED || job.status == Status.CANCELLED
    Row(
        Modifier.fillMaxWidth().clickable(enabled = job.detail != null && failed) { onDetail(job) }
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbBox(netImage(job.thumbnail), 112.dp, 63.dp, AppIcons.Movie, dim = job.status == Status.PAUSED || failed)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(Texts.title(job), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            if (job.status.active || job.status == Status.PAUSED || job.status == Status.QUEUED) {
                val p = job.progress
                val tint = if (job.status == Status.PAUSED) colors.onSurfaceVariant else colors.primary
                if (p != null && (job.status == Status.DOWNLOADING || job.status == Status.PAUSED)) {
                    LinearProgressIndicator(
                        progress = { p }, color = tint, trackColor = colors.surfaceVariant, strokeCap = StrokeCap.Round,
                        gapSize = 0.dp, drawStopIndicator = {}, modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                } else if (job.status != Status.PAUSED && job.status != Status.QUEUED) {
                    LinearProgressIndicator(color = tint, trackColor = colors.surfaceVariant, strokeCap = StrokeCap.Round, gapSize = 0.dp, modifier = Modifier.fillMaxWidth().height(4.dp))
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(
                Texts.status(job),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) colors.error else colors.onSurfaceVariant,
                maxLines = if (failed) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (failed) {
                Text(
                    Links.host(job.url) + if (job.detail != null) " · 눌러서 자세히" else "",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        when {
            job.status == Status.PAUSED -> RoundIcon(Icons.Rounded.PlayArrow, "이어받기", { app.resume(job.id) }, tint = colors.primary, iconSize = 26.dp)
            job.status.active || job.status == Status.QUEUED ->
                RoundIcon(AppIcons.Pause, "일시정지", { app.pause(job.id) }, enabled = job.status != Status.SAVING, tint = colors.onSurface)
            failed -> RoundIcon(Icons.Rounded.Refresh, "다시", { app.retry(job.id) }, tint = colors.primary)
        }
        RoundIcon(Icons.Rounded.Close, if (failed) "지우기" else "취소", { app.cancel(job.id) }, tint = colors.onSurfaceVariant, iconSize = 20.dp)
    }
}
