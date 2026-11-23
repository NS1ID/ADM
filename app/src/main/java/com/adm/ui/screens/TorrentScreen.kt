package com.adm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adm.download.TorrentTask
import java.util.UUID

/**
 * Torrent screen.
 *
 * The UI, the task model and the magnet parsing are here, but the torrent transport
 * is **not bundled** in this build: adding one means pulling a native BitTorrent
 * library (libtorrent or a Rust crate such as `librqbit`/`cratetorrent`) through the
 * Rust core. Until then tasks are accepted and shown as Queued so the screen and the
 * data flow are demonstrable, and the note below is honest about it.
 */
@Composable
fun TorrentScreen() {
    var magnet by remember { mutableStateOf("") }
    val tasks = remember { mutableStateListOf<TorrentTask>() }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Torrents", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = magnet,
            onValueChange = { magnet = it },
            label = { Text("magnet:?xt=urn:btih:...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val link = magnet.trim()
                if (link.isEmpty()) return@Button
                tasks.add(
                    0,
                    TorrentTask(
                        id = UUID.randomUUID().toString(),
                        magnet = link,
                        name = displayName(link),
                    ),
                )
                magnet = ""
            },
        ) {
            Text("Add magnet")
        }

        Text(
            "The torrent engine is not bundled in this build, so added magnet links stay " +
                "Queued. Wiring it up means adding a BitTorrent library to the Rust core " +
                "(see MERGE.md); the screen, the task model and persistence are ready.",
            style = MaterialTheme.typography.labelSmall,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(task.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(task.magnet, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        Text("Status: ${task.status.name.lowercase()}")
                    }
                }
            }
        }
    }
}

/** A readable name from a magnet link's `dn` parameter, else the info hash. */
private fun displayName(magnet: String): String {
    val dn = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)
    if (!dn.isNullOrBlank()) {
        return runCatching { java.net.URLDecoder.decode(dn, "UTF-8") }.getOrDefault(dn)
    }
    val hash = Regex("btih:([0-9a-fA-F]{6,})").find(magnet)?.groupValues?.get(1)
    return hash?.take(12)?.let { "Torrent $it" } ?: "Torrent"
}
