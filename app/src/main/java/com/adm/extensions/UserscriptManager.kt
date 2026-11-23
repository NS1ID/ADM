package com.adm.extensions

import android.content.Context
import com.adm.core.NativeCore
import com.adm.data.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Owns everything user-installable:
 *
 * * **Userscripts** — parsed by the Rust core (or a Kotlin fallback) and injected by
 *   [com.adm.browser.ChromiumEngine] at the document-start / document-end stage.
 * * **URL rules** — applied through the Rust rule engine to rewrite, block or add
 *   headers to requests.
 * * **Extensions** — a manifest bundle that may carry a userscript plus rules.
 *
 * Rows live in the `userscripts` / `extensions` tables created by [AppDatabase].
 */
object UserscriptManager {

    private val _scripts = MutableStateFlow<List<Userscript>>(emptyList())
    val scripts: StateFlow<List<Userscript>> = _scripts.asStateFlow()

    private val _extensions = MutableStateFlow<List<Extension>>(emptyList())
    val extensions: StateFlow<List<Extension>> = _extensions.asStateFlow()

    private val rules = mutableListOf<UrlRule>()

    fun init(context: Context) {
        loadScripts()
        loadExtensions()
        seedBundledExtensions()
    }

    // ---- userscripts -------------------------------------------------------

    private fun loadScripts() {
        val out = mutableListOf<Userscript>()
        runCatching {
            AppDatabase.get().readableDatabase
                .rawQuery("SELECT * FROM userscripts ORDER BY name", null)
                .use { c ->
                    while (c.moveToNext()) {
                        out += Userscript(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            name = c.getString(c.getColumnIndexOrThrow("name")),
                            namespace = c.getString(c.getColumnIndexOrThrow("namespace")) ?: "",
                            version = c.getString(c.getColumnIndexOrThrow("version")) ?: "",
                            description = c.getString(c.getColumnIndexOrThrow("description")) ?: "",
                            matches = c.getString(c.getColumnIndexOrThrow("matches"))
                                ?.split('\u0001')?.filter { it.isNotBlank() } ?: emptyList(),
                            excludes = emptyList(),
                            runAt = c.getString(c.getColumnIndexOrThrow("run_at")) ?: "document-idle",
                            source = c.getString(c.getColumnIndexOrThrow("source")),
                            enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
                        )
                    }
                }
        }
        _scripts.value = out
    }

    /** Parse + persist a userscript, returning the stored record. */
    fun installScript(source: String): Userscript {
        val meta = parseMetadata(source)
        val script = Userscript(
            id = UUID.randomUUID().toString(),
            name = meta.name,
            namespace = meta.namespace,
            version = meta.version,
            description = meta.description,
            matches = meta.matches,
            excludes = meta.excludes,
            runAt = meta.runAt,
            source = source,
            enabled = true,
        )
        saveScript(script)
        _scripts.value = _scripts.value + script
        return script
    }

    fun saveScript(script: Userscript) {
        AppDatabase.get().runAsync { db ->
            db.execSQL(
                """
                INSERT OR REPLACE INTO userscripts
                (id,name,namespace,version,description,matches,source,enabled,run_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                arrayOf<Any?>(
                    script.id, script.name, script.namespace, script.version,
                    script.description, script.matches.joinToString("\u0001"),
                    script.source, if (script.enabled) 1 else 0, script.runAt,
                )
            )
        }
    }

    fun setScriptEnabled(id: String, enabled: Boolean) {
        _scripts.value = _scripts.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        _scripts.value.firstOrNull { it.id == id }?.let { saveScript(it) }
    }

    fun removeScript(id: String) {
        _scripts.value = _scripts.value.filterNot { it.id == id }
        AppDatabase.get().runAsync { it.delete("userscripts", "id = ?", arrayOf(id)) }
    }

    /** Sources to inject into `url` for a given document `stage`. */
    fun scriptsFor(url: String, stage: String): List<String> {
        val sourceStage = when (stage) {
            "document-start" -> setOf("document-start")
            else -> setOf("document-end", "document-idle", "document-body")
        }
        return _scripts.value.filter { script ->
            script.enabled && script.runAt in sourceStage && matches(script, url)
        }.map { wrap(it) }
    }

    private fun matches(script: Userscript, url: String): Boolean {
        val meta = script.metaJson()
        if (NativeCore.available) return NativeCore.userscriptMatches(meta, url)
        return script.matches.any { glob(it, url) } && script.excludes.none { glob(it, url) }
    }

    private fun wrap(script: Userscript): String = """
        (function(){
          /* ADM+ userscript: ${script.name} v${script.version} */
          try {
            ${script.source}
          } catch (e) { console.error('ADM+ userscript error', e); }
        })();
    """.trimIndent()

    // ---- URL rules ---------------------------------------------------------

    fun setRules(newRules: List<UrlRule>) {
        rules.clear()
        rules.addAll(newRules)
    }

    fun addRule(rule: UrlRule) {
        rules += rule
    }

    fun removeRule(id: String) {
        rules.removeAll { it.id == id }
    }

    /** Apply rules through the Rust engine; returns `` when unavailable. */
    fun applyRules(url: String): JSONObject? {
        if (rules.isEmpty() || !NativeCore.available) return null
        val array = JSONArray().apply { rules.forEach { put(it.toJson()) } }
        return NativeCore.applyRules(array.toString(), url)
    }

    // ---- extensions --------------------------------------------------------

    private fun loadExtensions() {
        val out = mutableListOf<Extension>()
        runCatching {
            AppDatabase.get().readableDatabase
                .rawQuery("SELECT * FROM extensions ORDER BY name", null)
                .use { c ->
                    while (c.moveToNext()) {
                        val source = c.getString(c.getColumnIndexOrThrow("source")) ?: ""
                        out += Extension(
                            id = c.getString(c.getColumnIndexOrThrow("id")),
                            name = c.getString(c.getColumnIndexOrThrow("name")),
                            version = c.getString(c.getColumnIndexOrThrow("version")) ?: "",
                            author = c.getString(c.getColumnIndexOrThrow("author")) ?: "",
                            description = c.getString(c.getColumnIndexOrThrow("description")) ?: "",
                            enabled = c.getInt(c.getColumnIndexOrThrow("enabled")) == 1,
                            script = if (source.isNotBlank()) installPreview(source) else null,
                            manifest = c.getString(c.getColumnIndexOrThrow("manifest")) ?: "",
                        )
                    }
                }
        }
        _extensions.value = out
    }

    private fun installPreview(source: String): Userscript = Userscript(
        id = "preview",
        name = parseMetadata(source).name,
        namespace = "",
        version = "",
        description = "",
        matches = parseMetadata(source).matches,
        excludes = emptyList(),
        runAt = parseMetadata(source).runAt,
        source = source,
    )

    fun installExtension(ext: Extension) {
        AppDatabase.get().runAsync { db ->
            db.execSQL(
                """
                INSERT OR REPLACE INTO extensions
                (id,name,version,author,description,manifest,source,enabled,installed_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                arrayOf<Any?>(
                    ext.id, ext.name, ext.version, ext.author, ext.description,
                    ext.manifest, ext.script?.source ?: "", if (ext.enabled) 1 else 0,
                    System.currentTimeMillis(),
                )
            )
        }
        _extensions.value = _extensions.value.filterNot { it.id == ext.id } + ext
        ext.script?.let { installScript(it.source) }
        if (ext.urlRules.isNotEmpty()) setRules(rules + ext.urlRules)
    }

    fun setExtensionEnabled(id: String, enabled: Boolean) {
        _extensions.value = _extensions.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    fun removeExtension(id: String) {
        _extensions.value = _extensions.value.filterNot { it.id == id }
        AppDatabase.get().runAsync { it.delete("extensions", "id = ?", arrayOf(id)) }
    }

    /** A small offline catalogue so the store is usable without a backend. */
    fun storeCatalog(): List<StoreEntry> = BUNDLED_STORE

    private fun seedBundledExtensions() {
        // Install a demo userscript the first time so the feature is discoverable.
        if (_scripts.value.isNotEmpty()) return
        installScript(
            """
            // ==UserScript==
            // @name         ADM+ Page Tweaks
            // @namespace    com.adm.userscript.demo
            // @version      1.0
            // @description  Example script: highlights download links and shows a banner.
            // @match        *://*/*
            // @run-at       document-end
            // @grant        none
            // ==/UserScript==
            (function () {
              var style = document.createElement('style');
              style.textContent = 'a[href$=".mp4"],a[href$=".zip"],a[href$=".apk"]{outline:2px solid #4c6ef5 !important;border-radius:4px;}';
              document.head.appendChild(style);
            })();
            """.trimIndent()
        )
    }

    // ---- metadata parsing (Kotlin fallback) --------------------------------

    private data class Meta(
        val name: String,
        val namespace: String,
        val version: String,
        val description: String,
        val matches: List<String>,
        val excludes: List<String>,
        val runAt: String,
    )

    private fun parseMetadata(source: String): Meta {
        // Prefer the Rust parser; fall back to a line scan.
        NativeCore.parseUserscript(source)?.let { json ->
            val matches = json.optJSONArray("matches")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()
            val excludes = json.optJSONArray("excludes")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()
            return Meta(
                name = json.optString("name", "Unnamed script"),
                namespace = json.optString("namespace"),
                version = json.optString("version"),
                description = json.optString("description"),
                matches = matches,
                excludes = excludes,
                runAt = json.optString("run_at", "document-idle"),
            )
        }
        var name = "Unnamed script"; var ns = ""; var ver = ""; var desc = ""
        var runAt = "document-idle"
        val matches = mutableListOf<String>(); val excludes = mutableListOf<String>()
        var inside = false
        source.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("// ==UserScript==") -> inside = true
                line.startsWith("// ==/UserScript==") -> inside = false
                inside && line.startsWith("// @") -> {
                    val body = line.removePrefix("//").trim().removePrefix("@")
                    val key = body.substringBefore(' ').lowercase()
                    val value = body.substringAfter(' ', "").trim()
                    when (key) {
                        "name" -> name = value
                        "namespace" -> ns = value
                        "version" -> ver = value
                        "description" -> desc = value
                        "match", "include" -> matches += value
                        "exclude" -> excludes += value
                        "run-at" -> runAt = value
                    }
                }
            }
        }
        return Meta(name, ns, ver, desc, matches, excludes, runAt)
    }

    private fun glob(pattern: String, text: String): Boolean {
        val regex = "^" + Regex.escape(pattern).replace("\\*", ".*") + "$"
        return Regex(regex).matches(text)
    }

    private val BUNDLED_STORE = listOf(
        StoreEntry(
            id = "userscript.download-helper",
            name = "Download Helper",
            description = "Adds a floating button to every page listing downloadable media.",
            author = "ADM+",
            category = "Utilities",
            downloadUrl = "https://example.org/store/download-helper.user.js",
            version = "1.0.0",
        ),
        StoreEntry(
            id = "userscript.yt-cleaner",
            name = "Video Page Cleaner",
            description = "Hides promotional sidebars on video sites.",
            author = "ADM+",
            category = "Privacy",
            downloadUrl = "https://example.org/store/video-cleaner.user.js",
            version = "1.2.0",
        ),
        StoreEntry(
            id = "rules.redirect-amp",
            name = "AMP Redirect",
            description = "Rewrites Accelerated Mobile Pages links to the canonical URL.",
            author = "ADM+",
            category = "Browsing",
            downloadUrl = "https://example.org/store/amp-redirect.json",
            version = "1.0.0",
        ),
    )
}
