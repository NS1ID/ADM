package com.adm.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.adm.R
import com.adm.ui.theme.MyComposeApplicationTheme

/**
 * Handles "Share link to ADM+".
 *
 * Any app can `ACTION_SEND` some `text/plain` here (a URL, or text containing a URL).
 * We pull the first URL out, let the user confirm or edit the destination file name,
 * then enqueue it in [DownloadEngine] and finish. The activity uses the transparent
 * theme so it appears as a lightweight dialog over the sending app.
 */
class AddDownloadActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = extractSharedText(intent)
        val url = shared?.let { firstUrl(it) }

        if (url.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            MyComposeApplicationTheme {
                var name by remember {
                    mutableStateOf(DownloadEngine.guessNameFromUrl(url))
                }
                Dialog(onDismissRequest = { finish() }) {
                    Card {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(getString(R.string.download_start), style = MaterialTheme.typography.titleMedium)
                            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("File name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = androidx.compose.ui.Alignment.End,
                            ) {
                                androidx.compose.foundation.layout.Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    TextButton(onClick = { finish() }) {
                                        Text(getString(R.string.download_cancel))
                                    }
                                    Button(onClick = {
                                        DownloadEngine.enqueue(url, suggestedName = name)
                                        DownloadService.start(this@AddDownloadActivity)
                                        finish()
                                    }) {
                                        Text(getString(R.string.download_start))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return intent?.data?.toString()
        return intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    }

    /** First `http(s)://...` token in arbitrary shared text. */
    private fun firstUrl(text: String): String? {
        val match = URL_REGEX.find(text) ?: return null
        return match.value.trimEnd('.', ',', ')', ']', '"', '\'')
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    }
}
