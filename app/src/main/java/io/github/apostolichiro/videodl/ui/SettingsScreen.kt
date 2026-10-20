package io.github.apostolichiro.videodl.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.apostolichiro.videodl.BuildConfig
import io.github.apostolichiro.videodl.EngineState
import io.github.apostolichiro.videodl.Saver
import io.github.apostolichiro.videodl.Store
import io.github.apostolichiro.videodl.Tabs
import io.github.apostolichiro.videodl.UpdateState
import io.github.apostolichiro.videodl.engine.Engine
import io.github.apostolichiro.videodl.engine.Lookups
import io.github.apostolichiro.videodl.engine.SearchEngine

@Composable
fun SettingsScreen(app: AppActions, tabs: Tabs) {
    val colors = MaterialTheme.colorScheme
    val update by Store.update.collectAsStateWithLifecycle()
    val engine by Store.engine.collectAsStateWithLifecycle()
    var parallel by remember { mutableStateOf(Store.maxParallel) }
    var adBlock by remember { mutableStateOf(Store.adBlock) }
    var search by remember { mutableStateOf(Store.searchEngine) }
    var pickSearch by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { ScreenTitle("설정") }
        if (update !is UpdateState.None) item { UpdateCard(update, app) }

        item { Section("다운로드") }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("동시에 받기", style = MaterialTheme.typography.bodyLarge)
                Text("한 번에 여러 개를 받을 때 몇 개씩 받을지", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { n ->
                        val on = n == parallel
                        Box(
                            Modifier.size(42.dp).clip(CircleShape)
                                .background(if (on) colors.primary else colors.surfaceVariant)
                                .clickable { parallel = n; Store.maxParallel = n }
                                .semantics { contentDescription = "동시에 ${n}개" },
                            contentAlignment = Alignment.Center,
                        ) { Text("$n", color = if (on) colors.onPrimary else colors.onSurface, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        item { Item("화질", "항상 최고 화질") {} }
        item { Item("저장 위치", "Download › ${Saver.FOLDER}") {} }

        item { Section("브라우저") }
        item {
            Row(
                Modifier.fillMaxWidth().clickable { adBlock = !adBlock; Store.adBlock = adBlock }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("광고·팝업 차단", style = MaterialTheme.typography.bodyLarge)
                    Text("광고 서버와 저절로 뜨는 창을 막아요", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(checked = adBlock, onCheckedChange = { adBlock = it; Store.adBlock = it })
            }
        }
        item { Item("검색 엔진", search.label) { pickSearch = true } }
        item { Item("열린 탭 모두 닫기", null) { tabs.closeAll(); Store.notice.value = "탭을 모두 닫았어요." } }
        item { Item("사이트 로그인 모두 지우기", "인스타그램 등 브라우저에서 로그인한 정보") { confirmLogout = true } }

        item { Section("앱") }
        item { Item("앱 버전", BuildConfig.VERSION_NAME + " · 새 버전 확인") { app.checkUpdate() } }
        item {
            val line = when (val e = engine) {
                EngineState.Preparing -> "처음 실행 준비 중…"
                EngineState.Updating -> "새 버전 확인 중…"
                is EngineState.Broken -> e.message
                is EngineState.Ready -> buildString {
                    append("yt-dlp ")
                    append(Engine.shortVersion(e.version) ?: "기본 버전")
                    when {
                        e.updateFailed -> append(" · 확인 실패 (인터넷 확인)")
                        e.checkedAt > 0 -> append(" · ${ago(e.checkedAt)} 확인")
                    }
                }
            }
            Item("다운로드 엔진", "$line · 눌러서 업데이트") { app.updateEngine() }
        }
        item {
            // How fast looking a video up is right now (to tell what's slow when it is).
            val status = Lookups.status
            val last = Engine.lastLookup
            Item("영상 정보 확인", "빠른 확인 $status" + (last?.let { " · 마지막 확인 $it" } ?: "")) {}
        }
    }

    if (pickSearch) {
        Choose("검색 엔진", SearchEngine.entries, search, { it.label }, onDismiss = { pickSearch = false }) {
            search = it
            Store.searchEngine = it
            pickSearch = false
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("사이트 로그인을 모두 지울까요?") },
            text = { Text("브라우저에서 로그인한 사이트에서 모두 로그아웃돼요.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; app.clearLogins() }) { Text("지우기", color = colors.error) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(title: String, value: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun <T> Choose(title: String, options: List<T>, selected: T, label: (T) -> String, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(o) }.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(20.dp).clip(CircleShape).background(if (o == selected) colors.primary else colors.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { if (o == selected) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.onPrimary)) }
                        Spacer(Modifier.width(14.dp))
                        Text(label(o), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun UpdateCard(state: UpdateState, app: AppActions) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(colors.primaryContainer).padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp),
    ) {
        Text("새 버전이 나왔어요", style = MaterialTheme.typography.titleSmall, color = colors.onPrimaryContainer)
        Spacer(Modifier.height(4.dp))
        when (state) {
            is UpdateState.Available -> {
                Text("버튼 한 번이면 받고 설치까지 돼요.", style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
                TextButton(onClick = { app.startUpdate(state.release) }, contentPadding = PaddingValues(0.dp)) { Text("지금 업데이트") }
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
                TextButton(onClick = { app.installUpdate(state.file) }, contentPadding = PaddingValues(0.dp)) { Text("설치하기") }
            }
            is UpdateState.Failed -> {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = colors.error)
                TextButton(onClick = { app.startUpdate(state.release) }, contentPadding = PaddingValues(0.dp)) { Text("다시") }
            }
            UpdateState.None -> Unit
        }
    }
}

/** The update card also shows on the home page so a new version isn't missed. */
@Composable
fun UpdateBanner(app: AppActions) {
    val update by Store.update.collectAsStateWithLifecycle()
    if (update !is UpdateState.None) UpdateCard(update, app)
}
