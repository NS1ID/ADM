package com.adm.browser

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.view.ViewGroup
import com.adm.data.SettingsStore
import com.adm.download.DownloadEngine
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumChromeClient
import com.cefrium.CefriumContextMenu
import com.cefrium.CefriumDownloadClient
import com.cefrium.CefriumLoadError
import com.cefrium.CefriumNavigationClient
import com.cefrium.CefriumProfile
import com.cefrium.CefriumRendererTerminationStatus
import com.cefrium.CefriumResourceInterceptor
import com.cefrium.CefriumResourceType
import java.util.concurrent.ConcurrentHashMap

/**
 * One live browser tab.
 *
 * `browser` is the bundled Chromium engine instance (CEF via the Cefrium SDK),
 * `view` is its surface container (a `FrameLayout`) that the Compose browser screen
 * places with `AndroidView`. Keeping both here means tab switching just swaps which
 * container is attached - the page, its scroll position and its media state stay
 * alive for every background tab.
 */
class ChromiumTab internal constructor(
    val id: String,
    val incognito: Boolean,
    internal val browser: CefriumBrowser,
    internal val view: ViewGroup,
)

/** Observable state of one live tab, consumed by the Compose browser UI. */
data class ChromiumTabState(
    val url: String = "",
    val title: String = "New tab",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val fullscreen: Boolean = false,
    val error: String? = null,
)

/**
 * The browser engine facade.
 *
 * This drives **bundled Chromium** through the Cefrium SDK - the exact engine Google
 * Chrome and Brave use (Blink rendering, V8 JavaScript, Skia graphics, multi-process),
 * pinned inside the APK so every device renders identically instead of borrowing
 * whatever the system WebView happens to be. One engine instance is kept per tab, and
 * our [BrowserTab] (a String id persisted in SQLite) maps onto it.
 *
 * All methods must be called on the UI thread, as the engine requires.
 */
object ChromiumEngine {

    private val states = ConcurrentHashMap<String, ChromiumTabState>()
    private val tabs = ConcurrentHashMap<String, ChromiumTab>()

    /** The Activity the tabs render in; set by the hosting screen. */
    @Volatile private var host: Activity? = null

    fun init(context: Context) {
        // The engine initialises itself through the SDK's ContentProvider at app
        // launch. Warm the ad-block filter lists so blocking is live before the first
        // page renders.
        AdBlocker.loadAndApply(context.applicationContext)
    }

    /** Bind the hosting Activity (tabs render into it). */
    fun attachActivity(activity: Activity) {
        host = activity
    }

    /** A safe default homepage for a brand-new tab. */
    fun homepage(): String = SettingsStore.homepage.ifBlank { DEFAULT_HOME }

    fun state(tabId: String): ChromiumTabState = states[tabId] ?: ChromiumTabState()

    /**
     * Create the engine instance for [tab] on first use. The returned `view` must then
     * be attached to the view hierarchy by the hosting screen.
     */
    fun tabFor(tab: BrowserTab): ChromiumTab {
        tabs[tab.id]?.let { return it }
        val activity = host ?: error("ChromiumEngine.attachActivity() was not called")
        return synchronized(this) {
            tabs[tab.id]?.let { return it }
            val browser = if (tab.incognito) {
                CefriumBrowser.createWithSurface(activity, true)
            } else {
                CefriumBrowser.createWithSurface(activity, CefriumProfile.getDefault())
            }
            applySettings(browser)
            browser.setNavigationClient(NavigationClient(tab.id))
            browser.setChromeClient(ChromeClient(tab.id))
            browser.setDownloadClient(DownloadClient(tab.id))
            browser.setResourceInterceptor(ResourceClient(tab.id))
            val created = ChromiumTab(tab.id, tab.incognito, browser, browser.surfaceContainer)
            tabs[tab.id] = created
            created
        }
    }

    /** Apply the user's browse settings to one engine instance. */
    fun applySettings(browser: CefriumBrowser) {
        browser.setJavaScriptEnabled(SettingsStore.javascriptEnabled)
        if (SettingsStore.userAgent.isNotBlank()) {
            browser.setUserAgentOverride(SettingsStore.userAgent)
        }
        browser.setDarkMode(isSystemDark())
    }

    /** Re-apply browse settings to every live instance (called from Settings). */
    fun applySettingsToAll() {
        tabs.values.forEach { runCatching { applySettings(it.browser) } }
    }

    private fun isSystemDark(): Boolean {
        val mode = host?.resources?.configuration?.uiMode ?: return false
        return (mode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun closeTab(tabId: String) {
        states.remove(tabId)
        tabs.remove(tabId)?.let { tab ->
            (tab.view.parent as? ViewGroup)?.removeView(tab.view)
            runCatching { tab.browser.close() }
        }
    }

    fun closeAll() {
        tabs.keys.toList().forEach { closeTab(it) }
    }

    /** Clear Chromium's on-disk cache/site data (used by "clear data on exit"). */
    fun clearData() {
        tabs.values.forEach { runCatching { it.browser.clearCache() } }
        runCatching {
            CefriumProfile.getDefault().clearSiteData("https://www.google.com", null)
        }
    }

    internal fun updateState(tabId: String, transform: (ChromiumTabState) -> ChromiumTabState) {
        val previous = state(tabId)
        val next = transform(previous)
        states[tabId] = next
        TabManager.update(tabId) {
            it.copy(
                url = next.url.ifBlank { it.url },
                title = next.title,
                loading = next.loading,
                canGoBack = next.canGoBack,
                canGoForward = next.canGoForward,
                progress = next.progress,
            )
        }
        // Record history only on the loading -> finished transition. `updateState`
        // fires for every title change, progress tick and URL change too, so
        // recording unconditionally would insert the same page dozens of times.
        if (previous.loading && !next.loading && next.url.startsWith("http")) {
            com.adm.data.AppDatabase.get().recordVisit(next.url, next.title, false)
        }
    }

    /** Hand a sniffed URL to the media download candidates list. */
    internal fun onSniffed(url: String, mime: String) {
        MediaSniffer.observe(url, mime)
    }

    /** Chromium decided a response is a download rather than a page. */
    internal fun onExternalDownload(url: String, mime: String, filename: String?, referer: String?) {
        DownloadEngine.enqueue(
            url = url,
            suggestedName = filename,
            mime = mime.ifBlank { null },
            referer = referer,
        )
    }

    // ---- engine clients ----------------------------------------------------

    private class NavigationClient(private val tabId: String) : CefriumNavigationClient {
        override fun onUrlChanged(browser: CefriumBrowser, url: String) {
            ChromiumEngine.updateState(tabId) { it.copy(url = url) }
        }

        override fun onLoadStart(browser: CefriumBrowser, url: String) {
            ChromiumEngine.updateState(tabId) {
                it.copy(loading = true, progress = 5, error = null, url = url)
            }
        }

        override fun onLoadingStateChanged(
            browser: CefriumBrowser,
            loading: Boolean,
            canGoBack: Boolean,
            canGoForward: Boolean,
        ) {
            ChromiumEngine.updateState(tabId) {
                it.copy(
                    loading = loading,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    progress = if (loading) it.progress else 100,
                )
            }
        }

        override fun onProgressChanged(browser: CefriumBrowser, percent: Int) {
            ChromiumEngine.updateState(tabId) { it.copy(progress = percent) }
        }

        override fun onLoadError(browser: CefriumBrowser, error: CefriumLoadError) {
            val text = error.description.ifBlank { "Could not load page" }
            ChromiumEngine.updateState(tabId) { it.copy(error = text, loading = false) }
        }

        override fun onRenderProcessTerminated(
            browser: CefriumBrowser,
            status: CefriumRendererTerminationStatus,
            errorCode: Int,
        ) {
            ChromiumEngine.updateState(tabId) {
                it.copy(error = "Renderer stopped ($status, code $errorCode)", loading = false)
            }
        }
    }

    private class ChromeClient(private val tabId: String) : CefriumChromeClient {
        override fun onTitleChanged(browser: CefriumBrowser, title: String) {
            if (title.isBlank()) return
            ChromiumEngine.updateState(tabId) { it.copy(title = title) }
        }

        override fun onFullscreenChanged(browser: CefriumBrowser, fullscreen: Boolean) {
            ChromiumEngine.updateState(tabId) { it.copy(fullscreen = fullscreen) }
        }

        override fun onFaviconUrlsChanged(browser: CefriumBrowser, urls: MutableList<String>) {
            urls.firstOrNull()?.let { icon ->
                TabManager.update(tabId) { it.copy(favicon = icon) }
            }
        }

        /** Long-press on a link/image: offer the target to the media sniffer. */
        override fun onContextMenu(browser: CefriumBrowser, menu: CefriumContextMenu): Boolean {
            val uri = menu.sourceUrl.ifBlank { menu.linkUrl }
            if (uri.isNotBlank()) ChromiumEngine.onSniffed(uri, "")
            return false
        }
    }

    private class DownloadClient(private val tabId: String) : CefriumDownloadClient {
        override fun onBeforeDownload(
            browser: CefriumBrowser,
            item: CefriumBrowser.DownloadItem,
            callback: CefriumBrowser.BeforeDownloadCallback,
        ) {
            // Cancel Chromium's own transfer and hand the URL to the ADM+ engine so
            // the download gets segmentation, pause/resume and the downloads UI.
            callback.cancel()
            val url = item.url ?: return
            if (url.isBlank()) return
            if (SettingsStore.autoSniffDownload || looksLikeFile(url)) {
                ChromiumEngine.onExternalDownload(
                    url,
                    item.mimeType.orEmpty(),
                    item.suggestedName,
                    ChromiumEngine.state(tabId).url,
                )
            } else {
                // Otherwise surface it as a media candidate the user can confirm.
                ChromiumEngine.onSniffed(url, item.mimeType.orEmpty())
            }
        }
    }

    private class ResourceClient(private val tabId: String) : CefriumResourceInterceptor {
        /**
         * Network thread, must be fast. Layer 2 of ad blocking: the Rust ABP matcher.
         * (Layer 1, Chromium's own EasyList filter, runs before this inside the
         * engine and needs no Java callback at all.)
         */
        override fun shouldBlockRequest(request: CefriumBrowser.ResourceRequest): Boolean {
            if (request.isMainFrame) return false // never block the page itself
            return AdBlocker.shouldBlock(
                request.url,
                request.documentUrl.orEmpty(),
                typeName(request.type),
            )
        }

        /** Layer 1 reported a block: count it for the badge. */
        override fun onRequestFiltered(url: String, method: String, blocked: Boolean) {
            if (blocked) AdBlocker.recordEngineBlock(url)
        }

        override fun onResourceLoadComplete(url: String, statusCode: Int, receivedBytes: Long) {
            if (statusCode in 200..299 && receivedBytes > 512 * 1024) {
                ChromiumEngine.onSniffed(url, mimeFromUrl(url))
            }
        }
    }
}

// ---- file-local helpers (kept outside the object so nested clients can use them) ----

private const val DEFAULT_HOME = "https://www.google.com"

private val FILE_EXT = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "flv", "m4v", "ts", "3gp",
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus",
    "zip", "rar", "7z", "tar", "gz", "iso",
    "apk", "apks", "xapk", "obb", "pdf", "epub", "m3u8", "mpd",
)

private fun looksLikeFile(url: String): Boolean {
    val path = url.substringBefore('#').substringBefore('?').lowercase()
    return FILE_EXT.any { path.endsWith(".$it") }
}

private fun typeName(type: CefriumResourceType): String = when (type) {
    CefriumResourceType.SCRIPT -> "script"
    CefriumResourceType.IMAGE -> "image"
    CefriumResourceType.STYLESHEET -> "stylesheet"
    CefriumResourceType.FONT -> "font"
    CefriumResourceType.MEDIA -> "media"
    CefriumResourceType.XHR -> "xmlhttprequest"
    CefriumResourceType.MAIN_FRAME, CefriumResourceType.SUB_FRAME -> "document"
    else -> "other"
}

private fun mimeFromUrl(url: String): String {
    val ext = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
    return when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m3u8" -> "application/vnd.apple.mpegurl"
        "mpd" -> "application/dash+xml"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        else -> ""
    }
}