package com.adm.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI / FFI bridge to the Rust core (`libadm_core.so`, crate `adm_core`).
 *
 * The library is **optional**: if it was not cross-compiled for the device ABI the
 * class degrades gracefully and every call returns a safe fallback. All the heavier
 * work (segmented downloading, ad-block matching, media sniffing, the local proxy)
 * is available in pure Kotlin as well, so the app is fully usable either way — the
 * Rust paths simply make it faster and shareable with the Flutter/Dart front-end.
 *
 * Rust side: `rust/adm_core/src/jni_bridge.rs`.
 */
object NativeCore {

    private const val TAG = "AdmNativeCore"

    /** True once the shared library has been found and its symbols resolved. */
    val available: Boolean

    private val loaded = AtomicBoolean(false)

    init {
        available = try {
            System.loadLibrary("adm_core")
            loaded.set(true)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "libadm_core.so not bundled (${t.javaClass.simpleName}); " +
                    "using Kotlin fallbacks. Build it with rust/build-android.sh", t)
            false
        }
    }

    /** Must be called once from [com.adm.AdmApplication.onCreate]. */
    fun init(context: Context) {
        if (!available) return
        val dir = File(context.filesDir, "native-logs").apply { mkdirs() }
        runCatching { nativeInit(dir.absolutePath) }
            .onFailure { Log.w(TAG, "nativeInit failed", it) }
    }

    // ---- lifecycle ---------------------------------------------------------

    fun version(): String = if (available) runCatching { nativeVersion() }.getOrDefault("n/a") else "n/a"

    fun drainLog(): String = if (available) runCatching { nativeLogDrain() }.getOrDefault("") else ""

    // ---- media sniffer -----------------------------------------------------

    /**
     * Classify and de-duplicate a URL observed by the browser engine.
     * @return the sniffed item as JSON, or `` when it is not a download candidate.
     */
    fun sniff(url: String, mime: String): JSONObject? {
        if (!available) return null
        return runCatching {
            val json = JSONObject(nativeSniff(url, mime))
            if (json.optBoolean("candidate")) json.optJSONObject("item") else null
        }.getOrNull()
    }

    /** Stateless classification (no de-duplication). */
    fun classify(url: String, mime: String): JSONObject? {
        if (!available) return null
        return runCatching { JSONObject(nativeClassify(url, mime)) }.getOrNull()
    }

    fun resetSniffer() {
        if (available) runCatching { nativeSnifferReset() }
    }

    // ---- downloads ---------------------------------------------------------

    data class DownloadRequest(
        val id: String,
        val url: String,
        val path: String,
        val connections: Int = 8,
        val proxy: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val userAgent: String? = null,
    )

    fun startDownload(request: DownloadRequest): Boolean {
        if (!available) return false
        val obj = JSONObject().apply {
            put("id", request.id)
            put("url", request.url)
            put("path", request.path)
            put("connections", request.connections)
            request.proxy?.let { put("proxy", it) }
            request.userAgent?.let { put("userAgent", it) }
            put("headers", JSONObject(request.headers as Map<*, *>))
        }
        return runCatching { nativeDownloadStart(obj.toString()) == 0 }.getOrDefault(false)
    }

    fun downloadProgress(id: String): JSONObject? {
        if (!available) return null
        return runCatching { JSONObject(nativeDownloadProgress(id)) }.getOrNull()
    }

    enum class Command(val wire: String) { PAUSE("pause"), RESUME("resume"), CANCEL("cancel"), REMOVE("remove") }

    fun downloadCommand(id: String, command: Command): Boolean =
        available && runCatching { nativeDownloadCommand(id, command.wire) == 0 }.getOrDefault(false)

    fun suggestedFilename(url: String, contentDisposition: String?): String? {
        if (!available) return null
        return runCatching { nativeSuggestedFilename(url, contentDisposition ?: "") }.getOrNull()
    }

    // ---- ad blocking -------------------------------------------------------

    /** Load an ABP / hosts list, returning `{"rules":N}` stats. */
    fun loadAdblockList(list: String): JSONObject? {
        if (!available) return null
        return runCatching { JSONObject(nativeAdblockLoad(list)) }.getOrNull()
    }

    fun shouldBlock(url: String, pageUrl: String, resourceType: String): Boolean =
        available && runCatching { nativeAdblockShouldBlock(url, pageUrl, resourceType) }.getOrDefault(false)

    fun cosmeticCss(pageUrl: String): String =
        if (available) runCatching { nativeAdblockCosmeticCss(pageUrl) }.getOrDefault("") else ""

    fun adblockStats(): JSONObject? =
        if (available) runCatching { JSONObject(nativeAdblockStats()) }.getOrNull() else null

    fun clearAdblock() {
        if (available) runCatching { nativeAdblockClear() }
    }

    // ---- rules & userscripts ----------------------------------------------

    fun applyRules(rulesJson: String, url: String): JSONObject? {
        if (!available) return null
        return runCatching { JSONObject(nativeApplyRules(rulesJson, url)) }.getOrNull()
    }

    fun parseUserscript(source: String): JSONObject? {
        if (!available) return null
        return runCatching { JSONObject(nativeParseUserscript(source)) }.getOrNull()
    }

    fun userscriptMatches(metaJson: String, url: String): Boolean =
        available && runCatching { nativeUserscriptMatches(metaJson, url) }.getOrDefault(false)

    // ---- proxy / VPN -------------------------------------------------------

    /** Start the local proxy; returns `{"ok":true,"listen":"127.0.0.1:port"}` or `{"ok":false}`. */
    fun startProxy(port: Int, config: JSONObject): JSONObject? {
        if (!available) return null
        return runCatching { JSONObject(nativeProxyStart(port, config.toString())) }.getOrNull()
    }

    fun stopProxy(port: Int) {
        if (available) runCatching { nativeProxyStop(port) }
    }

    fun proxyStats(port: Int): JSONObject? {
        if (!available) return null
        return runCatching { JSONObject(nativeProxyStats(port)) }.getOrNull()
    }

    // ---- native declarations ----------------------------------------------

    private external fun nativeInit(logDir: String): Int
    private external fun nativeVersion(): String
    private external fun nativeLogDrain(): String

    private external fun nativeSniff(url: String, mime: String): String
    private external fun nativeClassify(url: String, mime: String): String
    private external fun nativeSnifferReset()

    private external fun nativeDownloadStart(optsJson: String): Int
    private external fun nativeDownloadProgress(id: String): String
    private external fun nativeDownloadCommand(id: String, cmd: String): Int
    private external fun nativeSuggestedFilename(url: String, disposition: String): String

    private external fun nativeAdblockLoad(list: String): String
    private external fun nativeAdblockShouldBlock(url: String, pageUrl: String, type: String): Boolean
    private external fun nativeAdblockCosmeticCss(pageUrl: String): String
    private external fun nativeAdblockStats(): String
    private external fun nativeAdblockClear()

    private external fun nativeApplyRules(rulesJson: String, url: String): String
    private external fun nativeParseUserscript(source: String): String
    private external fun nativeUserscriptMatches(metaJson: String, url: String): Boolean

    private external fun nativeProxyStart(port: Int, cfgJson: String): String
    private external fun nativeProxyStop(port: Int)
    private external fun nativeProxyStats(port: Int): String
}
