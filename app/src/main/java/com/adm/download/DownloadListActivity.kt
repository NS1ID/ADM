package com.adm.download

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.adm.R
import com.adm.ui.screens.DownloadsScreen
import com.adm.ui.theme.MyComposeApplicationTheme

/**
 * Downloads screen host.
 *
 * The download notification and "open downloads" actions point here. It renders the
 * same [DownloadsScreen] the bottom-navigation tab uses, so there is a single
 * implementation of the list, its filters and its per-item actions.
 */
class DownloadListActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val highlightId = intent?.getStringExtra(DownloadService.EXTRA_ID)
        setContent {
            MyComposeApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DownloadsScreen(
                        highlightId = highlightId,
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-open focused on the item the notification referred to.
        recreate()
    }
}
