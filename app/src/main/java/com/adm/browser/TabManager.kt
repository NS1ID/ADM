package com.adm.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One browser tab. */
data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String = "",
    val title: String = "New tab",
    val incognito: Boolean = false,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val progress: Int = 0,
    val favicon: String? = null,
)

/**
 * In-memory tab manager shared by the tab strip and the browser host.
 *
 * The engine instance for each tab is owned by
 * [com.adm.browser.ChromiumEngine], keyed by [BrowserTab.id], so switching tabs
 * preserves page state, scroll position and media playback the way users expect.
 */
object TabManager {

    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    val active: BrowserTab?
        get() = _tabs.value.firstOrNull { it.id == _activeId.value }

    /** Create a tab; `select` makes it the visible one. */
    fun newTab(url: String = "", incognito: Boolean = false, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(url = url, incognito = incognito)
        _tabs.value = _tabs.value + tab
        if (select) _activeId.value = tab.id
        return tab
    }

    fun close(id: String) {
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeId.value == id) {
            _activeId.value = remaining.lastOrNull()?.id
        }
    }

    fun closeAll() {
        _tabs.value = emptyList()
        _activeId.value = null
    }

    fun select(id: String) {
        if (_tabs.value.any { it.id == id }) _activeId.value = id
    }

    /** Patch one tab immutably; safe to call from WebView callbacks. */
    fun update(id: String, transform: (BrowserTab) -> BrowserTab) {
        _tabs.value = _tabs.value.map { if (it.id == id) transform(it) else it }
    }

    fun clearIncognito() {
        val ids = _tabs.value.filter { it.incognito }.map { it.id }
        ids.forEach { close(it) }
    }
}
