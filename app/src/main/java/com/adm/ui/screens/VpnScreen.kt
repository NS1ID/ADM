package com.adm.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.adm.data.SettingsStore
import com.adm.download.humanBytes
import com.adm.vpn.AdmVpnService
import com.adm.vpn.VpnState

/**
 * VPN screen: connect/disconnect the on-device tunnel, show live counters and edit the
 * tunnel options (upstream DNS, system-wide ad blocking).
 */
@Composable
fun VpnScreen() {
    val context = LocalContext.current
    val state by AdmVpnService.stateFlow().collectAsState()
    val stats by AdmVpnService.statsFlow().collectAsState()
    var blockAds by remember { mutableStateOf(SettingsStore.vpnBlockAds) }
    var dns by remember { mutableStateOf(SettingsStore.vpnUpstreamDns) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("On-device VPN", style = MaterialTheme.typography.titleLarge)
        Text(
            "Routes other apps' traffic through the ADM+ tunnel so the same rule engine " +
                "that blocks ads in the browser also blocks them system-wide.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Status: ${state.name}", style = MaterialTheme.typography.titleMedium)

        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Up: ${humanBytes(stats.bytesUp)}")
                Text("Down: ${humanBytes(stats.bytesDown)}")
                Text("Active connections: ${stats.activeConnections}")
                Text("Blocked: ${stats.blocked}")
            }
        }

        OutlinedTextField(
            value = dns,
            onValueChange = { dns = it },
            label = { Text("Upstream DNS") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = blockAds, onCheckedChange = { blockAds = it })
            Text("Block ads / trackers inside the tunnel")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                SettingsStore.vpnUpstreamDns = dns
                SettingsStore.vpnBlockAds = blockAds
                val consent = AdmVpnService.prepare(context)
                if (consent != null) {
                    context.startActivity(consent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    AdmVpnService.start(context)
                }
            }) {
                Text(if (state == VpnState.CONNECTED) "Reconnect" else "Connect")
            }
            OutlinedButton(
                onClick = { AdmVpnService.stop(context) },
                enabled = state == VpnState.CONNECTED,
            ) {
                Text("Disconnect")
            }
        }

        Text(
            "Note: the interface, routing, per-app split tunnelling, DNS and the proxy " +
                "core are implemented. Bridging raw packets from the TUN device needs a " +
                "user-space TCP/IP stack, which is the one remaining integration point; " +
                "until then the tunnel connects but does not carry other apps' traffic.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
