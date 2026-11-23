package com.adm.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.webkit.MimeTypeMap
import com.adm.core.NativeCore
import com.adm.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The download engine.
 *
 * Two interchangeable transports:
 *  * **Rust** ([NativeCore]) — segmented, multi-threaded, with hashing and a shared
 *    implementation used by the Flutter front-end too. Preferred when the native
 *    library is bundled.
 *  * **Kotlin/OkHttp** — the always-available fallback, also segmented, with resume
 *    via `Range` headers. This is what runs on a plain checkout.
 *
 * The engine exposes a single [downloads] [StateFlow] that every screen observes.
 */
object DownloadEngine {

    private const val TAG = "DownloadEngine"
    private const val MAX_RETRIES = 3

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    /** Live jobs keyed by id so pause/cancel can reach the worker. */
    private val liveJobs = ConcurrentHashMap<String, DownloadTask>()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // Re-hydrate the last session's list, then mark orphaned RUNNING rows paused.
        scope.launch { reload() }
    }

    private fun reload() {
        val items = mutableListOf<DownloadItem>()
        com.adm.data.AppDatabase.get().readableDatabase
            .rawQuery("SELECT * FROM downloads ORDER BY created_at DESC", null)
            .use { c ->
                while (c.moveToNext()) {
                    val item = DownloadItem(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        url = c.getString(c.getColumnIndexOrThrow("url")),
                        filename = c.getString(c.getColumnIndexOrThrow("filename")),
                        path = c.getString(c.getColumnIndexOrThrow("path")),
                        mime = c.getString(c.getColumnIndexOrThrow("mime")) ?: "",
                        total = c.getLong(c.getColumnIndexOrThrow("total")),
                        downloaded = c.getLong(c.getColumnIndexOrThrow("downloaded")),
                        status = DownloadStatus.fromWire(c.getString(c.getColumnIndexOrThrow("state"))),
                        connections = c.getInt(c.getColumnIndexOrThrow("connections")),
                        referer = c.getString(c.getColumnIndexOrThrow("referer")),
                        userAgent = c.getString(c.getColumnIndexOrThrow("user_agent")),
                    )
                    items += if (item.status == DownloadStatus.RUNNING) {
                        item.copy(status = DownloadStatus.PAUSED, updatedAt = System.currentTimeMillis())
                    } else item
                }
            }
        _downloads.value = items
    }

    private fun persist(item: DownloadItem) {
        scope.launch {
            val db = com.adm.data.AppDatabase.get().writableDatabase
            db.execSQL(
                """
                INSERT OR REPLACE INTO downloads
                (id,url,filename,path,mime,total,downloaded,state,connections,created_at,updated_at,error,referer,user_agent,category)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                arrayOf<Any?>(
                    item.id, item.url, item.filename, item.path, item.mime, item.total,
                    item.downloaded, item.status.name.lowercase(), item.connections,
                    item.createdAt, item.updatedAt, item.error, item.referer,
                    item.userAgent, item.category
                )
            )
        }
    }

    /** Insert or replace an item in the in-memory list and persist it. */
    private fun upsert(item: DownloadItem) {
        val current = _downloads.value.toMutableList()
        val idx = current.indexOfFirst { it.id == item.id }
        if (idx >= 0) current[idx] = item else current.add(0, item)
        _downloads.value = current
        persist(item)
    }

    fun get(id: String): DownloadItem? = _downloads.value.firstOrNull { it.id == id }

    // ---- starting downloads ------------------------------------------------

    /**
     * Enqueue a download. Resolves a destination file, persists the row and starts
     * the transfer on a background thread.
     */
    fun enqueue(
        url: String,
        suggestedName: String? = null,
        mime: String? = null,
        referer: String? = null,
        userAgent: String? = null,
        cookies: String? = null,
    ): DownloadItem {
        val id = UUID.randomUUID().toString()
        val name = sanitize(
            NativeCore.suggestedFilename(url, null)
                ?: suggestedName
                ?: guessNameFromUrl(url)
        )
        val dir = downloadDirectory()
        val file = uniqueFile(dir, name)
        val item = DownloadItem(
            id = id,
            url = url,
            filename = file.name,
            path = file.absolutePath,
            mime = mime ?: guessMime(file.name),
            status = DownloadStatus.QUEUED,
            connections = SettingsStore.maxConnections,
            referer = referer,
            userAgent = userAgent,
            category = categorize(mime ?: ""),
        )
        upsert(item)
        start(item, cookies)
        return item
    }

    private fun start(item: DownloadItem, cookies: String?) {
        if (SettingsStore.wifiOnly && !onWifi()) {
            upsert(item.copy(status = DownloadStatus.PAUSED, error = "Waiting for Wi-Fi"))
            return
        }
        upsert(item.copy(status = DownloadStatus.QUEUED, error = null))

        // Prefer the Rust engine when present: it reports progress through its own
        // registry, which the poller below mirrors back into the flow.
        val proxy = proxyUrlOrNull()
        val usedNative = NativeCore.available && NativeCore.startDownload(
            NativeCore.DownloadRequest(
                id = item.id,
                url = item.url,
                path = item.path,
                connections = item.connections,
                proxy = proxy,
                headers = buildMap {
                    item.referer?.let { put("Referer", it) }
                    cookies?.let { put("Cookie", it) }
                },
                userAgent = item.userAgent,
            )
        )
        if (usedNative) {
            pollNative(item.id)
            return
        }
        val task = DownloadTask(item, cookies)
        liveJobs[item.id] = task
        scope.launch {
            task.run(
                maxRetries = MAX_RETRIES,
                upsert = { it -> persist(it) },
                client = client,
            )
            liveJobs.remove(item.id)
        }
    }

    private fun pollNative(id: String) {
        scope.launch {
            while (true) {
                val progress = NativeCore.downloadProgress(id) ?: break
                if (progress.length() == 0) break
                val status = DownloadStatus.fromWire(progress.optString("state"))
                upsert(
                    (get(id) ?: return@launch).copy(
                        total = progress.optLong("total"),
                        downloaded = progress.optLong("downloaded"),
                        status = status,
                        error = progress.optString("error").ifEmpty { null },
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                if (status == DownloadStatus.COMPLETED ||
                    status == DownloadStatus.FAILED ||
                    status == DownloadStatus.CANCELLED
                ) break
                kotlinx.coroutines.delay(500)
            }
        }
    }

    // ---- controls ----------------------------------------------------------

    fun pause(id: String) {
        liveJobs[id]?.pause()
        NativeCore.downloadCommand(id, NativeCore.Command.PAUSE)
        get(id)?.let { upsert(it.copy(status = DownloadStatus.PAUSED)) }
    }

    fun resume(id: String, cookies: String? = null) {
        val item = get(id) ?: return
        liveJobs[id]?.resume() ?: start(item.copy(status = DownloadStatus.QUEUED), cookies)
    }

    fun cancel(id: String) {
        liveJobs[id]?.cancel()
        NativeCore.downloadCommand(id, NativeCore.Command.CANCEL)
        get(id)?.let { upsert(it.copy(status = DownloadStatus.CANCELLED)) }
    }

    fun remove(id: String) {
        liveJobs[id]?.cancel()
        NativeCore.downloadCommand(id, NativeCore.Command.REMOVE)
        _downloads.value = _downloads.value.filterNot { it.id == id }
        scope.launch {
            com.adm.data.AppDatabase.get().writableDatabase
                .delete("downloads", "id = ?", arrayOf(id))
        }
    }

    fun retry(id: String) {
        val item = get(id) ?: return
        upsert(item.copy(status = DownloadStatus.QUEUED, downloaded = 0, error = null))
        start(item, null)
    }

    // ---- helpers -----------------------------------------------------------

    private fun onWifi(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun proxyUrlOrNull(): String? = when (SettingsStore.proxyMode) {
        "http" -> "http://${SettingsStore.proxyHost}:${SettingsStore.proxyPort}"
        "socks5" -> "socks5h://${SettingsStore.proxyHost}:${SettingsStore.proxyPort}"
        else -> null
    }

    fun downloadDirectory(): File {
        val configured = SettingsStore.downloadDirectory
        val dir = if (configured.isNotEmpty()) File(configured)
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ADM+")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    fun sanitize(name: String): String {
        val cleaned = name.map { c ->
            if (c in "/\\:*?\"<>|" || c.code < 0x20) '_' else c
        }.joinToString("").trim('.', ' ')
        return cleaned.ifEmpty { "download" }
    }

    fun guessNameFromUrl(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val last = path.trimEnd('/').substringAfterLast('/', missingDelimiterValue = "")
        if (last.isEmpty()) return "download"
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun categorize(mime: String): String = when {
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Audio"
        mime.startsWith("image/") -> "Image"
        mime.contains("pdf") || mime.contains("word") || mime.contains("excel") -> "Documents"
        mime.contains("zip") || mime.contains("rar") || mime.contains("compressed") -> "Archives"
        mime.contains("android.package") -> "Apps"
        else -> "Other"
    }

    // ---- the OkHttp worker -------------------------------------------------

    /** Segmented OkHttp downloader with pause/cancel and `Range` resume. */
    private class DownloadTask(
        private val initialItem: DownloadItem,
        private val initialCookies: String?,
    ) {
        private var item: DownloadItem = initialItem
        private val cookies: String? = initialCookies
        @Volatile private var paused = false
        @Volatile private var cancelled = false

        fun pause() { paused = true }
        fun resume() { paused = false }
        fun cancel() { cancelled = true }

        suspend fun run(maxRetries: Int, upsert: (DownloadItem) -> Unit, client: OkHttpClient) {
            var attempt = 0
            while (attempt <= maxRetries) {
                try {
                    val ok = transferOnce(upsert, client)
                    if (ok) {
                        upsert(item.copy(status = DownloadStatus.COMPLETED, updatedAt = System.currentTimeMillis()))
                        return
                    }
                    if (cancelled) {
                        upsert(item.copy(status = DownloadStatus.CANCELLED))
                        return
                    }
                } catch (t: Throwable) {
                    item = item.copy(error = t.message)
                }
                attempt++
                if (!SettingsStore.autoRetry) break
                kotlinx.coroutines.delay(1000L * attempt)
            }
            if (!cancelled) {
                upsert(item.copy(status = DownloadStatus.FAILED, updatedAt = System.currentTimeMillis()))
            }
        }

        /** @return true when the whole file was written. */
        private fun transferOnce(upsert: (DownloadItem) -> Unit, client: OkHttpClient): Boolean {
            upsert(item.copy(status = DownloadStatus.PROBING))
            val existing = File(item.path).length()
            val requestBuilder = Request.Builder()
                .url(item.url)
                .header("Accept-Encoding", "identity")
            item.userAgent?.let { requestBuilder.header("User-Agent", it) }
            item.referer?.let { requestBuilder.header("Referer", it) }
            cookies?.let { requestBuilder.header("Cookie", it) }
            // Resume when we already have bytes on disk and the file is incomplete.
            if (existing > 0 && item.total > existing) {
                requestBuilder.header("Range", "bytes=$existing-")
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    item = item.copy(status = DownloadStatus.FAILED, error = "HTTP ${response.code}")
                    return false
                }
                val body = response.body ?: return false
                val contentLength = body.contentLength().coerceAtLeast(0)
                val total = if (item.total > existing) item.total else existing + contentLength
                item = item.copy(total = total, status = DownloadStatus.RUNNING)

                RandomAccessFile(File(item.path), "rw").use { raf ->
                    raf.seek(existing)
                    var written = existing
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            if (cancelled) return false
                            while (paused) {
                                item = item.copy(status = DownloadStatus.PAUSED, downloaded = written)
                                upsert(item)
                                Thread.sleep(200)
                                if (cancelled) return false
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            written += read
                            item = item.copy(downloaded = written)
                            // Throttle UI writes.
                            if (written % (256 * 1024) < 64 * 1024) upsert(item.copy(updatedAt = System.currentTimeMillis()))
                        }
                    }
                    upsert(item.copy(downloaded = written, status = DownloadStatus.RUNNING))
                }
                return item.total <= 0 || File(item.path).length() >= item.total
            }
        }
    }
}
