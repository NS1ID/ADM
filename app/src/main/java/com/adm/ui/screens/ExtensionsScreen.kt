package com.adm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adm.extensions.Extension
import com.adm.extensions.StoreEntry
import com.adm.extensions.Userscript
import com.adm.extensions.UserscriptManager

/**
 * Extensions screen: userscripts, packaged extensions and the offline store.
 *
 * The engine is bundled Chromium, whose extension system is Chrome's, but loading an
 * unpacked Chrome extension requires the engine to expose an extension API (CEF does
 * not, and neither does the Cefrium facade). So this screen manages the injected-
 * script form, which works with any engine: Greasemonkey userscripts and URL rules are
 * injected with `addDocumentStartJavaScript` / the resource interceptor. A full Chrome
 * MV3 extension loader is a documented follow-up (see MERGE.md).
 */
@Composable
fun ExtensionsScreen() {
    val scripts by UserscriptManager.scripts.collectAsState()
    val extensions by UserscriptManager.extensions.collectAsState()
    var installOpen by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Extensions", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { installOpen = true }) { Text("Install userscript") }
        }

        SectionTitle("Userscripts (${scripts.size})")
        if (scripts.isEmpty()) {
            Text("No userscripts installed.", style = MaterialTheme.typography.bodyMedium)
        }
        scripts.forEach { script -> UserscriptRow(script) }

        SectionTitle("Extensions (${extensions.size})")
        if (extensions.isEmpty()) {
            Text("No extensions installed.", style = MaterialTheme.typography.bodyMedium)
        }
        extensions.forEach { ext -> ExtensionRow(ext) }

        SectionTitle("Store (offline catalogue)")
        UserscriptManager.storeCatalog().forEach { entry -> StoreRow(entry) }
    }

    if (installOpen) {
        AlertDialog(
            onDismissRequest = { installOpen = false },
            title = { Text("Install userscript") },
            text = {
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("// ==UserScript== ... source") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (source.isNotBlank()) {
                        UserscriptManager.installScript(source)
                        source = ""
                    }
                    installOpen = false
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { installOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UserscriptRow(script: Userscript) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(script.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${script.matches.firstOrNull() ?: "*"} • ${script.runAt}",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            Switch(
                checked = script.enabled,
                onCheckedChange = { UserscriptManager.setScriptEnabled(script.id, it) },
            )
            IconButton(onClick = { UserscriptManager.removeScript(script.id) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove")
            }
        }
    }
}

@Composable
private fun ExtensionRow(ext: Extension) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ext.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    "${ext.author} • ${ext.version}".ifBlank { "Extension" },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            Switch(
                checked = ext.enabled,
                onCheckedChange = { UserscriptManager.setExtensionEnabled(ext.id, it) },
            )
            IconButton(onClick = { UserscriptManager.removeExtension(ext.id) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove")
            }
        }
    }
}

@Composable
private fun StoreRow(entry: StoreEntry) {
    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(entry.description, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            Text("${entry.category} • v${entry.version}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}
