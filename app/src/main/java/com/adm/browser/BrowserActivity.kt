package com.adm.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.adm.download.DownloadEngine
import com.adm.ui.screens.BrowserScreen
import com.adm.ui.theme.MyComposeApplicationTheme

/**
 * Dedicated browser host.
 *
 * Two entry points land here:
 *  * `ACTION_VIEW` with an `http(s)` URL — open it as a tab.
 *  * `ACTION_VIEW` with a downloadable MIME type (video, audio, PDF, zip, APK) —
 *    hand it straight to [DownloadEngine] instead of trying to render it, which is
 *    what a download manager is for.
 *
 * The launcher activity is `MainActivity`; this one exists so links and files from
 * other apps open directly into the browser or the download engine.
 */
class BrowserActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChromiumEngine.attachActivity(this)

        val target = intent?.let { resolveTarget(it) }
        if (target != null && target.download) {
            // A file link was shared/viewed: queue it and finish; the downloads
            // foreground service shows progress.
            DownloadEngine.enqueue(
                url = target.url,
                suggestedName = target.name,
                mime = target.mime,
            )
            com.adm.download.DownloadService.start(this)
            finish()
            return
        }

        if (target != null && TabManager.tabs.value.isEmpty()) {
            TabManager.newTab(url = target.url, select = true)
        }
        if (TabManager.tabs.value.isEmpty()) {
            TabManager.newTab(url = ChromiumEngine.homepage(), select = true)
        }

        setContent {
            MyComposeApplicationTheme {
                // Back inside a tab walks page history first, then leaves.
                val activeId = TabManager.activeId.value
                val state = activeId?.let { ChromiumEngine.state(it) }
                BackHandler(enabled = state?.canGoBack == true) {
                    TabManager.activeId.value?.let { id ->
                        ChromiumEngine.tabFor(TabManager.active ?: return@let).browser.goBack()
                    }
                }
                BrowserScreen(embedded = false, onExit = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resolveTarget(intent)?.let { target ->
            if (target.download) {
                DownloadEngine.enqueue(target.url, target.name, target.mime)
                com.adm.download.DownloadService.start(this)
            } else {
                TabManager.newTab(url = target.url, select = true)
            }
        }
    }

    /** What an incoming intent asks for, and whether it is a file rather than a page. */
    private data class Target(
        val url: String,
        val download: Boolean,
        val mime: String? = null,
        val name: String? = null,
    )

    private fun resolveTarget(intent: Intent): Target? {
        val data: Uri = intent.data ?: return null
        val mime = intent.type
        val isWeb = data.scheme == "http" || data.scheme == "https"

        if (isWeb) {
            val path = data.path.orEmpty().lowercase()
            val looksLikeFile = DOWNLOAD_MIME.any { mime?.startsWith(it) == true } ||
                DOWNLOAD_EXT.any { path.endsWith(".$it") }
            return Target(
                url = data.toString(),
                download = looksLikeFile,
                mime = mime ?: MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(path.substringAfterLast('.', "")),
            )
        }

        // content:// or file:// from another app: copy through the download engine.
        if (data.scheme == "content" || data.scheme == "file") {
            return Target(
                url = data.toString(),
                download = true,
                mime = mime ?: contentResolver.getType(data),
                name = data.lastPathSegment?.substringAfterLast('/'),
            )
        }
        return null
    }

    companion object {
        private val DOWNLOAD_MIME = listOf(
            "video/", "audio/", "application/pdf", "application/zip",
            "application/vnd.android.package-archive", "application/octet-stream",
        )
        private val DOWNLOAD_EXT = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "mp3", "m4a", "flac", "wav",
            "zip", "rar", "7z", "apk", "apks", "xapk", "obb", "pdf", "epub", "iso",
        )
    }
}
