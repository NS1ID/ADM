package com.adm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adm.browser.AdBlocker
import com.adm.browser.ChromiumEngine
import com.adm.data.SettingsStore
import com.cefrium.Cefrium

/**
 * Settings, grouped the way a download manager expects: General, Downloads, Privacy and
 * About (which reports the bundled engine version).
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var homepage by remember { mutableStateOf(SettingsStore.homepage) }
    var search by remember { mutableStateOf(SettingsStore.searchEngine) }
    var maxConn by remember { mutableStateOf(SettingsStore.maxConnections.toString()) }
    var retryCount by remember { mutableStateOf(SettingsStore.retryCount.toString()) }
    var wifiOnly by remember { mutableStateOf(SettingsStore.wifiOnly) }
    var jsEnabled by remember { mutableStateOf(SettingsStore.javascriptEnabled) }
    var adblock by remember { mutableStateOf(SettingsStore.adblockEnabled) }
    var dnt by remember { mutableStateOf(SettingsStore.doNotTrack) }
    var clearOnExit by remember { mutableStateOf(SettingsStore.clearOnExit) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text("General", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = homepage,
            onValueChange = { homepage = it },
            label = { Text("Homepage") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search engine URL prefix") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Downloads", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = maxConn,
            onValueChange = { maxConn = it.filter(Char::isDigit) },
            label = { Text("Max connections per download") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = retryCount,
            onValueChange = { retryCount = it.filter(Char::isDigit) },
            label = { Text("Retry attempts") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow("Only download on Wi-Fi", wifiOnly) { wifiOnly = it }

        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        ToggleRow("Ad and tracker blocking", adblock) { adblock = it }
        ToggleRow("Do Not Track header", dnt) { dnt = it }
        ToggleRow("JavaScript", jsEnabled) { jsEnabled = it }
        ToggleRow("Clear browsing data on exit", clearOnExit) { clearOnExit = it }

        Button(onClick = {
            SettingsStore.homepage = homepage.trim()
            SettingsStore.searchEngine = search.trim()
            SettingsStore.maxConnections = maxConn.toIntOrNull() ?: SettingsStore.maxConnections
            SettingsStore.retryCount = retryCount.toIntOrNull() ?: SettingsStore.retryCount
            SettingsStore.wifiOnly = wifiOnly
            SettingsStore.javascriptEnabled = jsEnabled
            SettingsStore.doNotTrack = dnt
            SettingsStore.clearOnExit = clearOnExit
            val wasBlocking = SettingsStore.adblockEnabled
            SettingsStore.adblockEnabled = adblock
            if (adblock && !wasBlocking) AdBlocker.loadAndApply(context) else if (!adblock) AdBlocker.reset()
            ChromiumEngine.applySettingsToAll()
        }) {
            Text("Save settings")
        }

        Text("About", style = MaterialTheme.typography.titleMedium)
        Text("ADM+ 1.0", style = MaterialTheme.typography.bodyMedium)
        Text("Browser engine: bundled Chromium", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Chromium version: ${runCatching { Cefrium.getChromiumVersion() }.getOrDefault("unknown")}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            "Engine SDK: ${runCatching { Cefrium.getVersion() }.getOrDefault("unknown")}",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
