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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.apostolichiro.videodl.Job
import io.github.apostolichiro.videodl.SavedFile
import io.github.apostolichiro.videodl.Saver
import io.github.apostolichiro.videodl.Status
import io.github.apostolichiro.videodl.Store
import io.github.apostolichiro.videodl.Texts
import io.github.apostolichiro.videodl.engine.Fmt
import io.github.apostolichiro.videodl.engine.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Kind(val label: String) { ALL("전체"), VIDEO("영상"), AUDIO("음악"), OTHER("기타") }

/** The 파일 tab: everything downloaded, newest first. */
@Composable
fun FilesScreen(app: AppActions) {
    val colors = MaterialTheme.colorScheme
    val jobs by Store.jobs.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(Kind.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    val all = remember(jobs) {
        jobs.filter { it.status == Status.DONE }.flatMap { j -> j.files.map { j to it } }
    }
    fun kindOf(f: SavedFile) = when {
        f.isVideo -> Kind.VIDEO
        f.isAudio -> Kind.AUDIO
        else -> Kind.OTHER
    }
    val kinds = all.map { kindOf(it.second) }.toSet()
    val q = query.trim()
    val shown = all.filter { (j, f) ->
        (kind == Kind.ALL || kindOf(f) == kind) &&
            (q.isEmpty() || listOf(Texts.title(j), f.name, j.url).any { it.contains(q, ignoreCase = true) })
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("파일") {
            if (all.isNotEmpty()) {
                RoundIcon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, if (searching) "검색 닫기" else "파일 검색", {
                    searching = !searching
                    if (!searching) query = ""
                })
            }
        }
        if (all.isEmpty()) {
            EmptyState(
                AppIcons.Folder,
                "받은 파일이 없어요",
                "다 받은 영상은 여기와 갤러리의 ${Saver.FOLDER} 앨범에 모여요.",
            )
            return@Column
        }
        if (searching) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("제목이나 사이트로 찾기") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )
        }
        if (kinds.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(Kind.entries.filter { it == Kind.ALL || it in kinds }) { k ->
                    FilterChip(
                        selected = kind == k, onClick = { kind = k },
                        label = { Text(if (k == Kind.ALL) "${k.label} ${all.size}" else k.label) },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primary, selectedLabelColor = colors.onPrimary,
                        ),
                        border = null,
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            Text(
                if (q.isNotEmpty()) "'$q'에 맞는 파일이 없어요." else "이 종류의 파일이 없어요.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.second.uri }) { (job, file) -> FileRow(job, file, app) }
            item(key = "where") {
                Text(
                    "저장 위치: 내장 저장공간 › Download › ${Saver.FOLDER}",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun FileRow(job: Job, file: SavedFile, app: AppActions) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val exists by produceState(true, file.uri) { value = withContext(Dispatchers.IO) { Saver.exists(ctx, file.uri) } }
    val duration = fileDuration(file)

    Row(
        Modifier.fillMaxWidth().clickable {
            if (exists) app.play(job, file) else deleting = true
        }.padding(start = 16.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbBox(
            fileThumb(file) ?: netImage(job.thumbnail), 128.dp, 72.dp,
            if (file.isAudio) AppIcons.Music else AppIcons.Movie,
            duration?.let { durationText(it) },
            dim = !exists,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                if (exists) "${Fmt.size(file.size)} · ${dayText(job.createdAt)} · ${Links.host(job.url)}" else "폰에서 지워진 파일이에요",
                style = MaterialTheme.typography.bodySmall,
                color = if (exists) colors.onSurfaceVariant else colors.error,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            RoundIcon(Icons.Rounded.MoreVert, "파일 메뉴", { menu = true }, tint = colors.onSurfaceVariant)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (exists) {
                    DropdownMenuItem(text = { Text("공유") }, onClick = { menu = false; app.share(listOf(file)) })
                    DropdownMenuItem(text = { Text("다른 앱으로 열기") }, onClick = { menu = false; app.openWith(file) })
                    DropdownMenuItem(text = { Text("이름 바꾸기") }, onClick = { menu = false; renaming = true })
                }
                if (job.url.startsWith("http")) {
                    DropdownMenuItem(text = { Text("원래 페이지 열기") }, onClick = { menu = false; app.openPage(job.referer ?: job.url) })
                }
                DropdownMenuItem(text = { Text("삭제", color = colors.error) }, onClick = { menu = false; deleting = true })
            }
        }
    }

    if (renaming) {
        val ext = file.name.substringAfterLast('.', "")
        var name by remember { mutableStateOf(file.name.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("이름 바꾸기") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, suffix = { if (ext.isNotEmpty()) Text(".$ext") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    app.rename(job, file, name.trim() + if (ext.isNotEmpty()) ".$ext" else "")
                }) { Text("바꾸기") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("취소") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(if (exists) "이 파일을 지울까요?" else "목록에서 뺄까요?") },
            text = { Text(file.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    if (exists) app.delete(job, file) else Store.removeFile(job.id, file.uri)
                }) { Text(if (exists) "지우기" else "빼기", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("취소") } },
        )
    }
}
