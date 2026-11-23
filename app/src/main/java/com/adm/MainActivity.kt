package com.adm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.adm.browser.ChromiumEngine
import com.adm.browser.TabManager
import com.adm.ui.screens.AiScreen
import com.adm.ui.screens.BrowserScreen
import com.adm.ui.screens.DownloadsScreen
import com.adm.ui.screens.ExtensionsScreen
import com.adm.ui.screens.ProxyScreen
import com.adm.ui.screens.SettingsScreen
import com.adm.ui.screens.TorrentScreen
import com.adm.ui.screens.VpnScreen
import com.adm.ui.theme.MyComposeApplicationTheme

/**
 * The launcher and app shell: a bottom navigation bar over the seven feature areas,
 * with the browser engine hosted by [BrowserScreen] inside whichever tab is selected.
 * Links shared from other apps arrive as `ACTION_VIEW` intents and open as tabs.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChromiumEngine.attachActivity(this)
        handleIntent(intent)
        if (TabManager.tabs.value.isEmpty()) {
            TabManager.newTab(url = ChromiumEngine.homepage(), select = true)
        }
        requestNotificationPermissionIfNeeded()

        setContent {
            MyComposeApplicationTheme {
                AppShell()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.data?.toString() ?: return
        if (url.startsWith("http")) {
            TabManager.newTab(url = url, select = true)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** One destination in the bottom bar. */
private enum class Destination(val label: String, val icon: ImageVector) {
    BROWSER("Browser", Icons.Filled.Public),
    AI("AI", Icons.Filled.AutoAwesome),
    DOWNLOADS("Downloads", Icons.Filled.Download),
    TORRENTS("Torrents", Icons.Filled.SwapVert),
    VPN("VPN", Icons.Filled.VpnLock),
    PROXY("Proxy", Icons.Filled.Router),
    EXTENSIONS("Add-ons", Icons.Filled.Extension),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
private fun AppShell() {
    var selected by remember { mutableStateOf(Destination.BROWSER) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = selected == destination,
                        onClick = { selected = destination },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            when (selected) {
                Destination.BROWSER -> BrowserScreen(embedded = true)
                Destination.AI -> AiScreen()
                Destination.DOWNLOADS -> DownloadsScreen()
                Destination.TORRENTS -> TorrentScreen()
                Destination.VPN -> VpnScreen()
                Destination.PROXY -> ProxyScreen()
                Destination.EXTENSIONS -> ExtensionsScreen()
                Destination.SETTINGS -> SettingsScreen()
            }
        }
    }
}