package com.adm.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Typed access to the app's preferences. A thin wrapper over [SharedPreferences]
 * so that every screen reads settings through one object and defaults live in one
 * place (mirroring the "General / Downloads / Privacy / Advanced" settings tree).
 */
object SettingsStore {

    private const val FILE = "adm_settings"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // NativeCore.init is done once in AdmApplication; it must not run twice.
    }

    // ---- browsing ----------------------------------------------------------

    var homepage: String
        get() = prefs.getString(KEY_HOMEPAGE, "https://www.google.com")!!
        set(v) = prefs.edit().putString(KEY_HOMEPAGE, v).apply()

    var searchEngine: String
        get() = prefs.getString(KEY_SEARCH, "https://www.google.com/search?q=")!!
        set(v) = prefs.edit().putString(KEY_SEARCH, v).apply()

    var desktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP, false)
        set(v) = prefs.edit().putBoolean(KEY_DESKTOP, v).apply()

    var incognitoByDefault: Boolean
        get() = prefs.getBoolean(KEY_INCOGNITO, false)
        set(v) = prefs.edit().putBoolean(KEY_INCOGNITO, v).apply()

    var javascriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JS, true)
        set(v) = prefs.edit().putBoolean(KEY_JS, v).apply()

    var imagesEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMAGES, true)
        set(v) = prefs.edit().putBoolean(KEY_IMAGES, v).apply()

    var userAgent: String
        get() = prefs.getString(KEY_UA, "")!!
        set(v) = prefs.edit().putString(KEY_UA, v).apply()

    // ---- downloads ---------------------------------------------------------

    var maxConnections: Int
        get() = prefs.getInt(KEY_CONN, 8)
        set(v) = prefs.edit().putInt(KEY_CONN, v.coerceIn(1, 16)).apply()

    var downloadDirectory: String
        get() = prefs.getString(KEY_DIR, "")!!
        set(v) = prefs.edit().putString(KEY_DIR, v).apply()

    var autoRetry: Boolean
        get() = prefs.getBoolean(KEY_RETRY, true)
        set(v) = prefs.edit().putBoolean(KEY_RETRY, v).apply()

    var retryCount: Int
        get() = prefs.getInt(KEY_RETRY_N, 3)
        set(v) = prefs.edit().putInt(KEY_RETRY_N, v.coerceIn(0, 10)).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(v) = prefs.edit().putBoolean(KEY_WIFI_ONLY, v).apply()

    // ---- ad blocking / privacy --------------------------------------------

    var adblockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(v) = prefs.edit().putBoolean(KEY_ADBLOCK, v).apply()

    var blockTrackers: Boolean
        get() = prefs.getBoolean(KEY_TRACKERS, true)
        set(v) = prefs.edit().putBoolean(KEY_TRACKERS, v).apply()

    var doNotTrack: Boolean
        get() = prefs.getBoolean(KEY_DNT, true)
        set(v) = prefs.edit().putBoolean(KEY_DNT, v).apply()

    var clearOnExit: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_EXIT, false)
        set(v) = prefs.edit().putBoolean(KEY_CLEAR_EXIT, v).apply()

    var adblockRulesLoaded: Boolean
        get() = prefs.getBoolean(KEY_RULES_LOADED, false)
        set(v) = prefs.edit().putBoolean(KEY_RULES_LOADED, v).apply()

    /** Auto-start a download when the sniffer finds a clear media/archive URL. */
    var autoSniffDownload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SNIFF, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_SNIFF, v).apply()

    // ---- proxy / vpn -------------------------------------------------------

    var proxyMode: String
        get() = prefs.getString(KEY_PROXY_MODE, "off")!!   // off | http | socks5 | vpn
        set(v) = prefs.edit().putString(KEY_PROXY_MODE, v).apply()

    var proxyHost: String
        get() = prefs.getString(KEY_PROXY_HOST, "")!!
        set(v) = prefs.edit().putString(KEY_PROXY_HOST, v).apply()

    var proxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, 0)
        set(v) = prefs.edit().putInt(KEY_PROXY_PORT, v).apply()

    var proxyUser: String
        get() = prefs.getString(KEY_PROXY_USER, "")!!
        set(v) = prefs.edit().putString(KEY_PROXY_USER, v).apply()

    var proxyPass: String
        get() = prefs.getString(KEY_PROXY_PASS, "")!!
        set(v) = prefs.edit().putString(KEY_PROXY_PASS, v).apply()

    var vpnBlockAds: Boolean
        get() = prefs.getBoolean(KEY_VPN_ADBLOCK, true)
        set(v) = prefs.edit().putBoolean(KEY_VPN_ADBLOCK, v).apply()

    var vpnUpstreamDns: String
        get() = prefs.getString(KEY_VPN_DNS, "1.1.1.1")!!
        set(v) = prefs.edit().putString(KEY_VPN_DNS, v).apply()

    /** Package names excluded from the tunnel (split tunnelling). */
    var vpnBypassPackages: Set<String>
        get() = prefs.getStringSet(KEY_VPN_BYPASS, emptySet())!!
        set(v) = prefs.edit().putStringSet(KEY_VPN_BYPASS, v).apply()

    private const val KEY_HOMEPAGE = "homepage"
    private const val KEY_SEARCH = "search"
    private const val KEY_DESKTOP = "desktop_mode"
    private const val KEY_INCOGNITO = "incognito"
    private const val KEY_JS = "javascript"
    private const val KEY_IMAGES = "images"
    private const val KEY_UA = "user_agent"
    private const val KEY_CONN = "connections"
    private const val KEY_DIR = "download_dir"
    private const val KEY_RETRY = "auto_retry"
    private const val KEY_RETRY_N = "retry_count"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_ADBLOCK = "adblock"
    private const val KEY_TRACKERS = "block_trackers"
    private const val KEY_DNT = "do_not_track"
    private const val KEY_CLEAR_EXIT = "clear_on_exit"
    private const val KEY_RULES_LOADED = "rules_loaded"
    private const val KEY_AUTO_SNIFF = "auto_sniff_download"
    private const val KEY_PROXY_MODE = "proxy_mode"
    private const val KEY_PROXY_HOST = "proxy_host"
    private const val KEY_PROXY_PORT = "proxy_port"
    private const val KEY_PROXY_USER = "proxy_user"
    private const val KEY_PROXY_PASS = "proxy_pass"
    private const val KEY_VPN_ADBLOCK = "vpn_adblock"
    private const val KEY_VPN_DNS = "vpn_dns"
    private const val KEY_VPN_BYPASS = "vpn_bypass"
}
