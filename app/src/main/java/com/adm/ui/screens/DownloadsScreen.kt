package com.adm.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.adm.R
import com.adm.download.DownloadEngine
import com.adm.download.DownloadItem
import com.adm.download.DownloadStatus
import com.adm.download.humanBytes
import java.io.File

/**
 * The downloads list: category filter, per-item progress and the pause/resume/cancel/
 * retry/open actions, all driven by [DownloadEngine.downloads].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(highlightId: String? = null, onBack: (() -> Unit)? = null) {
    val all by DownloadEngine.downloads.collectAsState()
    var filter by remember { mutableStateOf("All") }
    val categories = remember(all) { listOf("All") + all.mapNotNull { it.category }.distinct().sorted() }
    val items = remember(all, filter) {
        if (filter == "All") all else all.filter { it.category == filter }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (onBack != null) "Downloads" else "Downloads") },
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEach { cat ->
                FilterChip(
                    selected = filter == cat,
                    onClick = { filter = cat },
                    label = { Text(cat) },
                )
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No downloads yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    DownloadRow(item, highlight = item.id == highlightId)
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, highlight: Boolean) {
    val context = LocalContext.current
    Surface(
        color = if (highlight) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(item.filename, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                text = "${item.category ?: "Other"} • ${item.status.name.lowercase()} • " +
                    "${humanBytes(item.downloaded)} / ${humanBytes(item.total)}",
                style = MaterialTheme.typography.labelSmall,
            )
            if (!item.isTerminal) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                when (item.status) {
                    DownloadStatus.RUNNING, DownloadStatus.PROBING, DownloadStatus.QUEUED ->
                        IconButton(onClick = { DownloadEngine.pause(item.id) }) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pause")
                        }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                        IconButton(onClick = { DownloadEngine.resume(item.id) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Resume")
                        }
                    else -> Unit
                }
                if (item.isTerminal) {
                    IconButton(onClick = { DownloadEngine.retry(item.id) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                    }
                    if (item.status == DownloadStatus.COMPLETED) {
                        IconButton(onClick = { openFile(context, item.path, item.mime) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Open")
                        }
                    }
                }
                IconButton(onClick = { DownloadEngine.cancel(item.id) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                }
                IconButton(onClick = { DownloadEngine.remove(item.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                }
            }
        }
    }
}

/** Open a finished download with whatever app handles its type. */
private fun openFile(context: android.content.Context, path: String, mime: String) {
    runCatching {
        val file = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
