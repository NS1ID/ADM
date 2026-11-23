package com.adm.browser

import android.net.Uri
import com.adm.core.NativeCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** A media/asset candidate discovered while browsing. */
data class SniffedMedia(
    val url: String,
    val kind: String,
    val ext: String,
    val mime: String,
    val score: Int,
    val title: String,
) {
    companion object {
        fun fromJson(json: JSONObject): SniffedMedia = SniffedMedia(
            url = json.optString("url"),
            kind = json.optString("kind", "unknown"),
            ext = json.optString("ext"),
            mime = json.optString("mime"),
            score = json.optInt("score"),
            title = json.optString("title"),
        )
    }
}

/**
 * &quot;Downloads&quot; tab of the browser: collects the media the page requests.
 *
 * Prefers the Rust classifier (fast regex + de-duplication) and falls back to a
 * Kotlin heuristic when the native library is absent, so the feature works in both
 * build configurations.
 */
object MediaSniffer {

    private val _items = MutableStateFlow<List<SniffedMedia>>(emptyList())
    val items: StateFlow<List<SniffedMedia>> = _items.asStateFlow()

    private val seen = HashSet<String>()

    @Synchronized
    fun clear() {
        seen.clear()
        _items.value = emptyList()
        NativeCore.resetSniffer()
    }

    /** Feed one observed request; ignores duplicates and non-candidates. */
    @Synchronized
    fun observe(url: String, mime: String = "") {
        if (url.startsWith("data:") || url.startsWith("blob:")) return
        if (!seen.add(url)) return

        val native = NativeCore.sniff(url, mime)
        val item = if (native != null) {
            SniffedMedia.fromJson(native)
        } else {
            classifyLocally(url, mime) ?: return
        }
        _items.value = (_items.value + item).sortedByDescending { it.score }.take(200)
    }

    /** Kotlin fallback classifier — mirrors the essentials of the Rust one. */
    private fun classifyLocally(url: String, mime: String): SniffedMedia? {
        val lower = url.lowercase()
        NOISE.firstOrNull { lower.contains(it) }?.let { return null }
        val ext = Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        val (kind, score) = when {
            ext == "m3u8" || lower.contains(".m3u8") -> "hls" to 90
            ext == "mpd" -> "dash" to 90
            ext in VIDEO -> "video" to 85
            ext in AUDIO -> "audio" to 70
            ext in APPS -> "app" to 80
            ext in ARCHIVE -> "archive" to 60
            ext in DOCS -> "document" to 55
            ext in IMAGE -> "image" to 25
            mime.startsWith("video/") -> "video" to 80
            mime.startsWith("audio/") -> "audio" to 65
            else -> return null
        }
        return SniffedMedia(
            url = url,
            kind = kind,
            ext = ext,
            mime = mime,
            score = score,
            title = Uri.decode(Uri.parse(url).lastPathSegment ?: url),
        )
    }

    private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "m4v", "ts", "3gp")
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "svg")
    private val DOCS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "iso")
    private val APPS = setOf("apk", "apks", "xapk", "obb")
    private val NOISE = listOf(
        "google-analytics", "googletagmanager", "doubleclick", "scorecardresearch",
        "facebook.com/tr", "beacon", "adsystem", "adserver", "/pagead/", "favicon.ico"
    )
}
