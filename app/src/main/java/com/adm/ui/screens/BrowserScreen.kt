package com.adm.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.adm.browser.AdBlocker
import com.adm.browser.BrowserTab
import com.adm.browser.ChromiumEngine
import com.adm.browser.ChromiumTabState
import com.adm.browser.MediaSniffer
import com.adm.browser.TabManager
import com.adm.download.DownloadEngine
import com.adm.download.DownloadService

/**
 * The browser: address bar, navigation, the engine surface, the tab strip and the
 * media-candidate menu. Shared by `MainActivity`'s Browser tab (`embedded = true`) and
 * the dedicated `BrowserActivity` (`embedded = false`, which adds an exit action).
 */
@Composable
fun BrowserScreen(embedded: Boolean = true, onExit: () -> Unit = {}) {
    val tabs by TabManager.tabs.collectAsState()
    val activeId by TabManager.activeId.collectAsState()
    val media by MediaSniffer.items.collectAsState()
    val blocked by AdBlocker.blockedCount.collectAsState()

    // Creating the first tab mutates state, so it must not happen during composition.
    if (tabs.isEmpty()) {
        LaunchedEffect(Unit) { TabManager.newTab(url = ChromiumEngine.homepage(), select = true) }
        return
    }
    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.last()
    // `remember` keyed on the tab id: one engine instance per live tab, reused across
    // recompositions instead of being re-created on every state change.
    val tab = remember(active.id) { ChromiumEngine.tabFor(active) }
    // The engine's own state map is plain (non-observable) storage, so the UI reads the
    // values TabManager mirrors into its StateFlow - that is what drives recomposition.
    val state = ChromiumTabState(
        url = active.url,
        title = active.title,
        loading = active.loading,
        progress = active.progress,
        canGoBack = active.canGoBack,
        canGoForward = active.canGoForward,
    )

    Column(Modifier.fillMaxSize()) {
        AddressBar(
            url = state.url,
            loading = state.loading,
            progress = state.progress,
            canGoBack = state.canGoBack,
            canGoForward = state.canGoForward,
            blockedCount = blocked,
            mediaCount = media.size,
            onNavigate = { input -> loadInput(tab, input) },
            onBack = { runCatching { tab.browser.goBack() } },
            onForward = { runCatching { tab.browser.goForward() } },
            onReload = {
                runCatching { if (state.loading) tab.browser.stopLoading() else tab.browser.reload() }
            },
            onHome = { loadInput(tab, ChromiumEngine.homepage()) },
            onMedia = { url, kind ->
                DownloadEngine.enqueue(url, mime = kind)
                DownloadService.start(tab.view.context)
            },
            onExit = onExit,
            embedded = embedded,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(active.id) {
                AndroidView(
                    factory = { tab.view },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        TabStrip(
            tabs = tabs,
            activeId = active.id,
            onSelect = { TabManager.select(it) },
            onClose = {
                ChromiumEngine.closeTab(it)
                TabManager.close(it)
            },
            onNew = { TabManager.newTab(url = ChromiumEngine.homepage(), select = true) },
        )
    }
}

@Composable
private fun AddressBar(
    url: String,
    loading: Boolean,
    progress: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    blockedCount: Int,
    mediaCount: Int,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onMedia: (String, String) -> Unit,
    onExit: () -> Unit,
    embedded: Boolean,
) {
    var field by remember(url) { mutableStateOf(url) }
    var mediaOpen by remember { mutableStateOf(false) }
    val media by MediaSniffer.items.collectAsState()

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!embedded) {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit")
                    }
                }
                IconButton(onClick = onBack, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                IconButton(onClick = onForward, enabled = canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    modifier = Modifier.weight(1f).height(52.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onNavigate(field) }),
                    placeholder = { Text("Search or enter address") },
                )
                IconButton(onClick = onReload) {
                    Icon(Icons.Filled.Refresh, contentDescription = if (loading) "Stop" else "Reload")
                }
                IconButton(onClick = onHome) {
                    Icon(Icons.Filled.Home, contentDescription = "Home")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$blockedCount blocked",
                    style = MaterialTheme.typography.labelSmall,
                )
                Box {
                    AssistChip(
                        onClick = { mediaOpen = true },
                        label = { Text("Media ($mediaCount)") },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = mediaOpen, onDismissRequest = { mediaOpen = false }) {
                        if (media.isEmpty()) {
                            DropdownMenuItem(text = { Text("No media found on this page yet") }, onClick = { mediaOpen = false })
                        }
                        media.take(20).forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(item.title.ifBlank { item.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.kind, style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                onClick = {
                                    mediaOpen = false
                                    onMedia(item.url, item.kind)
                                },
                            )
                        }
                    }
                }
            }
            if (loading) {
                LinearProgressIndicator(
                    progress = { (progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TabStrip(
    tabs: List<BrowserTab>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                AssistChip(
                    onClick = { onSelect(tab.id) },
                    label = {
                        Text(
                            tab.title.ifBlank { "New tab" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close tab", Modifier.size(16.dp))
                        }
                    },
                    leadingIcon = if (tab.id == activeId) {
                        { Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(16.dp)) }
                    } else null,
                )
            }
            IconButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "New tab")
            }
        }
    }
}

/** Turn whatever the user typed into a URL (or a search) and load it. */
private fun loadInput(tab: com.adm.browser.ChromiumTab, input: String) {
    val text = input.trim()
    if (text.isEmpty()) return
    val url = when {
        text.contains("://") -> text
        text.startsWith("about:") || text.startsWith("adm:") -> text
        text.contains('.') && !text.contains(' ') -> "https://$text"
        else -> com.adm.data.SettingsStore.searchEngine + android.net.Uri.encode(text)
    }
    runCatching { tab.browser.loadUrl(url) }
}
