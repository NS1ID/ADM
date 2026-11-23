package com.adm.extensions

import com.adm.core.NativeCore
import org.json.JSONArray
import org.json.JSONObject

/** A Greasemonkey/Tampermonkey-style userscript. */
data class Userscript(
    val id: String,
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val matches: List<String>,
    val excludes: List<String>,
    val runAt: String,
    val source: String,
    val enabled: Boolean = true,
) {
    fun metaJson(): String = JSONObject().apply {
        put("name", name)
        put("namespace", namespace)
        put("version", version)
        put("description", description)
        put("matches", JSONArray(matches))
        put("includes", JSONArray(matches))
        put("excludes", JSONArray(excludes))
        put("run_at", runAt)
    }.toString()
}

/** A packaged extension: a manifest plus optional userscript/rule payloads. */
data class Extension(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val enabled: Boolean,
    /** `` when the extension only carries URL rules. */
    val script: Userscript?,
    /** Optional manifest.json verbatim, for display. */
    val manifest: String,
    val urlRules: List<UrlRule> = emptyList(),
)

/** One URL rewrite rule, matching the Rust `rules::UrlRule`. */
data class UrlRule(
    val id: String,
    val name: String,
    val pattern: String,
    val action: String,   // redirect | block | header | useragent
    val replacement: String,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("pattern", pattern)
        put("action", action)
        put("replacement", replacement)
        put("enabled", enabled)
    }
}

/** A repository entry in the built-in extension store. */
data class StoreEntry(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val category: String,
    val downloadUrl: String,
    val sha256: String = "",
    val version: String = "",
)
