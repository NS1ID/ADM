package com.adm.browser

import android.content.Context
import android.util.Log
import com.adm.core.NativeCore
import com.adm.data.SettingsStore
import com.cefrium.Cefrium
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Content blocker for the bundled Chromium engine.
 *
 * Under Chromium the blocking happens in **two layers**, both real:
 *
 *  1. **Engine-native EasyList filter** — [Cefrium.downloadAndLoadEasyList] /
 *     [Cefrium.loadAdBlockList]. Chromium cancels matching requests inside the
 *     network stack, before they are sent, with no Java callback at all. This is the
 *     same approach Brave Shields uses (`||host^`, `@@` exceptions, `$third-party`,
 *     resource-type options), and it is what makes blocking fast.
 *  2. **Rust ABP matcher** — [shouldBlock], called from
 *     [ChromiumEngine]'s `shouldBlockRequest` interceptor for the rules Chromium's
 *     filter does not cover (exact `$domain=` narrowing, our own bundled starter
 *     list/hosts entries, per-site exceptions).
 *
 * The same rule engine is also reused by the VPN layer for non-browser traffic, so
 * all three of ADM+'s blocking surfaces share one implementation.
 */
object AdBlocker {

    private const val TAG = "AdBlocker"

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    private val _ruleCount = MutableStateFlow(0)
    val ruleCount: StateFlow<Int> = _ruleCount.asStateFlow()

    /** Rules Chromium's own EasyList filter currently holds (0 until loaded). */
    private val _engineRules = MutableStateFlow(0)
    val engineRules: StateFlow<Int> = _engineRules.asStateFlow()

    fun init(context: Context) = loadAndApply(context)

    /**
     * Load the bundled starter list into the Rust matcher and pull the full EasyList
     * into Chromium's native filter. Safe to call more than once; the engine caches
     * the downloaded list for 24 hours and uses the last good copy on failure.
     */
    fun loadAndApply(context: Context) {
        if (!SettingsStore.adblockEnabled) return
        val listsDir = File(context.filesDir, "adblock").apply { mkdirs() }
        seedDefaultRules(listsDir)
        loadBundledLists(listsDir)
        loadEngineList(context)
        SettingsStore.adblockRulesLoaded = true
    }

    /** Install a small built-in ruleset the first time the app runs. */
    private fun seedDefaultRules(dir: File) {
        val marker = File(dir, "defaults.txt")
        if (marker.exists()) return
        runCatching { marker.writeText(DEFAULT_RULES) }
    }

    private fun loadBundledLists(dir: File) {
        val combined = StringBuilder()
        runCatching {
            dir.listFiles { f -> f.isFile }?.forEach { file ->
                combined.append(file.readText()).append('\n')
            }
        }
        load(combined.toString())
    }

    /** Layer 1: hand EasyList to Chromium so it blocks inside the network stack. */
    fun loadEngineList(context: Context) {
        if (!SettingsStore.adblockEnabled) return
        runCatching {
            Cefrium.downloadAndLoadEasyList(context) { rules, error ->
                if (rules >= 0) {
                    _engineRules.value = rules
                    _ruleCount.value = _ruleCount.value + rules
                } else {
                    Log.w(TAG, "EasyList unavailable: ${error ?: "unknown"} (using bundled rules)")
                }
            }
        }.onFailure { Log.w(TAG, "engine filter list failed", it) }
    }

    /** Load an ABP / hosts list into the Rust matcher; returns compiled rule count. */
    fun load(listText: String): Int {
        val native = NativeCore.loadAdblockList(listText)
        val rules = native?.optInt("rules") ?: 0
        if (native != null) {
            _ruleCount.value = rules
            return rules
        }
        // Fallback: count plausible network rules so the UI still shows a number.
        val fallback = listText.lineSequence().count { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("!") && !t.startsWith("#") && !t.startsWith("[")
        }
        _ruleCount.value = fallback
        return fallback
    }

    fun addListFile(context: Context, name: String, contents: String): Int {
        val dir = File(context.filesDir, "adblock").apply { mkdirs() }
        runCatching { File(dir, name).writeText(contents) }
        return load(contents)
    }

    /**
     * Layer 2: the Rust ABP matcher. Called on the network thread for every
     * sub-resource request, so it must stay cheap (the Rust side is).
     */
    fun shouldBlock(url: String, pageUrl: String, resourceType: String): Boolean {
        if (!SettingsStore.adblockEnabled) return false
        val blocked = NativeCore.shouldBlock(url, pageUrl, resourceType)
        if (blocked) _blockedCount.value += 1
        return blocked
    }

    /** CSS to hide leftover ad containers (injected as document-start JavaScript). */
    fun cosmeticCss(pageUrl: String): String {
        if (!SettingsStore.adblockEnabled) return ""
        return NativeCore.cosmeticCss(pageUrl)
    }

    /** Report a block Chromium's native filter performed (layer 1). */
    fun recordEngineBlock(url: String) {
        _blockedCount.value += 1
    }

    fun reset() {
        _blockedCount.value = 0
        NativeCore.clearAdblock()
    }

    /**
     * A compact starter ruleset. Deliberately small and readable — real users add
     * their own lists (Settings → Ad blocking → Add list), and the full EasyList is
     * downloaded into the engine's native filter.
     */
    private val DEFAULT_RULES = """
        ! ADM+ built-in filter list
        ! --- common ad / analytics hosts ---
        0.0.0.0 doubleclick.net
        0.0.0.0 google-analytics.com
        0.0.0.0 googletagmanager.com
        0.0.0.0 googlesyndication.com
        0.0.0.0 googleadservices.com
        0.0.0.0 adservice.google.com
        0.0.0.0 scorecardresearch.com
        0.0.0.0 outbrain.com
        0.0.0.0 taboola.com
        0.0.0.0 criteo.com
        0.0.0.0 adnxs.com
        0.0.0.0 adsrvr.org
        0.0.0.0 pubmatic.com
        0.0.0.0 rubiconproject.com
        ! --- ABP-style network rules ---
        ||ads.example.com^
        /pagead/
        ||analytics.*${'$'}third-party
        ! --- cosmetic rules ---
        example.com##.ad-banner
        example.com##div[id^="google_ads_"]
    """.trimIndent()
}