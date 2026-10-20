package io.github.apostolichiro.videodl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.apostolichiro.videodl.AppRelease
import io.github.apostolichiro.videodl.Job
import io.github.apostolichiro.videodl.NewJob
import io.github.apostolichiro.videodl.SavedFile
import io.github.apostolichiro.videodl.SheetRequest
import io.github.apostolichiro.videodl.Status
import io.github.apostolichiro.videodl.Store
import io.github.apostolichiro.videodl.Tabs
import io.github.apostolichiro.videodl.Texts
import java.io.File

/** Everything the screens ask the app to do. */
class AppActions(
    val download: (NewJob) -> Unit,
    val play: (Job, SavedFile) -> Unit,
    val openWith: (SavedFile) -> Unit,
    val share: (List<SavedFile>) -> Unit,
    val delete: (Job, SavedFile) -> Unit,
    val rename: (Job, SavedFile, String) -> Unit,
    /** Opens a web address in a new browser tab. */
    val openPage: (String) -> Unit,
    /** Reads a link from the clipboard and opens the download sheet for it. */
    val pasteLink: () -> Unit,
    val pause: (String) -> Unit,
    val resume: (String) -> Unit,
    val cancel: (String) -> Unit,
    val retry: (String) -> Unit,
    val copy: (String) -> Unit,
    val shareText: (String) -> Unit,
    val openExternal: (String) -> Unit,
    val startUpdate: (AppRelease) -> Unit,
    val installUpdate: (File) -> Unit,
    val checkUpdate: () -> Unit,
    val updateEngine: () -> Unit,
    val clearLogins: () -> Unit,
)

fun openSheet(r: SheetRequest) {
    Store.sheet.value = r
}

@Composable
fun AppRoot(app: AppActions, tabs: Tabs) {
    val tab by Store.tab.collectAsStateWithLifecycle()
    val jobs by Store.jobs.collectAsStateWithLifecycle()
    val notice by Store.notice.collectAsStateWithLifecycle()
    val sheet by Store.sheet.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val running = jobs.count { it.status.active || it.status == Status.QUEUED }
    // On a web page the bar below steps aside while the page is scrolled down (sites like Instagram size
    // their videos to the screen's height) and is back as soon as it's scrolled up.
    val browserTab by tabs.current.collectAsStateWithLifecycle()
    val pageUrl by browserTab.url.collectAsStateWithLifecycle()
    val barHidden by tabs.barHidden.collectAsStateWithLifecycle()
    val onWebPage = tab == Store.TAB_HOME && pageUrl.isNotEmpty()

    LaunchedEffect(notice) {
        val n = notice ?: return@LaunchedEffect
        Store.notice.value = null
        snackbar.showSnackbar(n)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!(onWebPage && barHidden)) BottomBar(tab, running) { Store.tab.value = it }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Store.TAB_PROGRESS -> ProgressScreen(app)
                Store.TAB_FILES -> FilesScreen(app)
                Store.TAB_SETTINGS -> SettingsScreen(app, tabs)
                else -> BrowserScreen(tabs, app)
            }
        }
    }

    sheet?.let { r -> key(r) { DownloadSheet(r, app) } }
}

@Composable
private fun BottomBar(tab: Int, running: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.background(colors.background)) {
        HorizontalDivider(color = colors.outlineVariant, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
            NavItem(Icons.Rounded.Home, "홈", tab == Store.TAB_HOME) { onSelect(Store.TAB_HOME) }
            NavItem(AppIcons.Downloading, "받는 중", tab == Store.TAB_PROGRESS, badge = running) { onSelect(Store.TAB_PROGRESS) }
            NavItem(AppIcons.Folder, "파일", tab == Store.TAB_FILES) { onSelect(Store.TAB_FILES) }
            NavItem(Icons.Rounded.Settings, "설정", tab == Store.TAB_SETTINGS) { onSelect(Store.TAB_SETTINGS) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(icon: ImageVector, label: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Column(
        Modifier.weight(1f).height(62.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(25.dp))
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-5).dp).size(17.dp).clip(CircleShape).background(LocalAccents.current.badge),
                    contentAlignment = Alignment.Center,
                ) { Text(if (badge > 9) "9+" else "$badge", color = androidx.compose.ui.graphics.Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Text(label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = tint, modifier = Modifier.padding(top = 3.dp))
    }
}
