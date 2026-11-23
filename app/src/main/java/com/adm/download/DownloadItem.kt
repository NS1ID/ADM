package com.adm.download

import org.json.JSONObject

/** Lifecycle of a download, mirroring the Rust `download::DownloadState`. */
enum class DownloadStatus {
    QUEUED, PROBING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED;

    companion object {
        fun fromWire(s: String?): DownloadStatus = when (s?.lowercase()) {
            "probing" -> PROBING
            "running" -> RUNNING
            "paused" -> PAUSED
            "completed" -> COMPLETED
            "failed" -> FAILED
            "cancelled" -> CANCELLED
            else -> QUEUED
        }
    }
}

/** In-memory + persisted view of one download. */
data class DownloadItem(
    val id: String,
    val url: String,
    val filename: String,
    val path: String,
    val mime: String = "",
    val total: Long = 0L,
    val downloaded: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val connections: Int = 8,
    val error: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val category: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    val isTerminal: Boolean
        get() = status == DownloadStatus.COMPLETED ||
                status == DownloadStatus.FAILED ||
                status == DownloadStatus.CANCELLED

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("filename", filename)
        put("path", path)
        put("mime", mime)
        put("total", total)
        put("downloaded", downloaded)
        put("state", status.name.lowercase())
        put("connections", connections)
        put("error", error ?: "")
        put("referer", referer ?: "")
        put("userAgent", userAgent ?: "")
        put("category", category ?: "")
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): DownloadItem = DownloadItem(
            id = json.optString("id"),
            url = json.optString("url"),
            filename = json.optString("filename"),
            path = json.optString("path"),
            mime = json.optString("mime"),
            total = json.optLong("total"),
            downloaded = json.optLong("downloaded"),
            status = DownloadStatus.fromWire(json.optString("state")),
            connections = json.optInt("connections", 8),
            error = json.optString("error").ifEmpty { null },
            referer = json.optString("referer").ifEmpty { null },
            userAgent = json.optString("userAgent").ifEmpty { null },
            category = json.optString("category").ifEmpty { null },
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
        )
    }
}

/** A torrent task (magnet / .torrent). The transport is a native libtorrent-style
 *  engine; when it is not bundled the task stays in [DownloadStatus.QUEUED] and the
 *  UI shows an explanatory hint. */
data class TorrentTask(
    val id: String,
    val magnet: String,
    val name: String = "",
    val total: Long = 0L,
    val downloaded: Long = 0L,
    val peers: Int = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val savePath: String = "",
)
