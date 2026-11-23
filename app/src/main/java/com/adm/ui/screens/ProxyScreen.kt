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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.adm.data.SettingsStore
import com.cefrium.CefriumProfile
import com.cefrium.CefriumProxyMode

/**
 * Proxy screen. The settings apply to both the browser engine (per-profile proxy) and
 * the download engine (the Rust core and the OkHttp fallback both read them), so one
 * change covers everything.
 */
@Composable
fun ProxyScreen() {
    var mode by remember { mutableStateOf(SettingsStore.proxyMode.ifBlank { "off" }) }
    var host by remember { mutableStateOf(SettingsStore.proxyHost) }
    var port by remember {
        mutableStateOf(if (SettingsStore.proxyPort > 0) SettingsStore.proxyPort.toString() else "")
    }
    var user by remember { mutableStateOf(SettingsStore.proxyUser) }
    var pass by remember { mutableStateOf(SettingsStore.proxyPass) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Proxy", style = MaterialTheme.typography.titleLarge)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("off", "http", "socks5").forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.uppercase()) })
            }
        }

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host") },
            singleLine = true,
            enabled = mode != "off",
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text("Port") },
            singleLine = true,
            enabled = mode != "off",
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("User name (optional)") },
            singleLine = true,
            enabled = mode != "off",
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password (optional)") },
            singleLine = true,
            enabled = mode != "off",
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = {
            SettingsStore.proxyMode = mode
            SettingsStore.proxyHost = host.trim()
            SettingsStore.proxyPort = port.toIntOrNull() ?: 0
            SettingsStore.proxyUser = user
            SettingsStore.proxyPass = pass
            applyProxy()
        }) {
            Text("Save and apply")
        }

        Text(
            "A fixed proxy has no direct fallback: when it cannot be reached, requests " +
                "fail rather than leaving without it. Loopback addresses are never proxied.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Push the saved proxy straight into the engine's default profile. */
fun applyProxy() {
    val profile = CefriumProfile.getDefault()
    val host = SettingsStore.proxyHost
    val port = SettingsStore.proxyPort
    runCatching {
        when (SettingsStore.proxyMode) {
            "http" -> profile.setProxy(CefriumProxyMode.FIXED, "http://$host:$port")
            "socks5" -> profile.setProxy(CefriumProxyMode.FIXED, "socks5://$host:$port")
            else -> profile.clearProxy()
        }
    }
}
