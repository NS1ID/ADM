package com.adm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.adm.ai.AiClient
import com.adm.ai.AiMessage
import com.adm.browser.ChromiumEngine
import com.adm.browser.TabManager
import com.adm.data.SettingsStore
import kotlinx.coroutines.launch

/**
 * Browser-wide AI assistant. Page context is opt-in and only ever attached when the
 * user sends a prompt (never silently uploaded). Configuration lives in Settings › AI.
 */
@Composable
fun AiScreen() {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf(listOf<AiMessage>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var pageText by remember { mutableStateOf<String?>(null) }

    fun send(prompt: String) {
        if (prompt.isBlank() || busy) return
        error = null
        busy = true
        messages = messages + AiMessage("user", prompt)
        input = ""

        val tab = TabManager.active
        val tabId = tab?.id
        if (tabId != null && SettingsStore.aiIncludePage) {
            runCatching {
                ChromiumEngine.tabFor(tab).evaluateJavaScript(
                    "(function(){return document.body?document.body.innerText.slice(0,6000):'';})()",
                ) { result -> pageText = result?.removeSurrounding("\"") }
            }
        }

        scope.launch {
            val result = AiClient.ask(
                prompt = prompt,
                pageTitle = tab?.title,
                pageUrl = tab?.url,
                pageText = pageText,
                history = messages.dropLast(1),
            )
            busy = false
            result.fold(
                onSuccess = { messages = messages + AiMessage("assistant", it) },
                onFailure = { error = it.message ?: "Request failed" },
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Assistant", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (SettingsStore.aiIncludePage) "Page context: on" else "Page context: off",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row {
                IconButton(onClick = { messages = emptyList(); error = null }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear chat")
                }
                TextButton(onClick = { showSettings = !showSettings }) {
                    Text(if (showSettings) "Hide setup" else "Setup")
                }
            }
        }
        HorizontalDivider()

        if (showSettings) {
            AiSettingsPanel()
            HorizontalDivider()
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ask about the page, or anything.", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Summarize, translate, explain, or draft — configured through your own API key in Setup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Suggestion("Summarize page") { send("Summarize the key points of this page in 5 bullets.") }
                            Suggestion("Translate") { send("Translate the main content of this page into clear English.") }
                        }
                    }
                }
            }
            items(messages) { msg ->
                val mine = msg.role == "user"
                Surface(
                    Modifier.fillMaxWidth(),
                    color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (mine) "You" else "Assistant",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(msg.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (busy) {
                item {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Text("  Thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (error != null) {
                item {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask a question…") },
                singleLine = true,
            )
            IconButton(onClick = { send(input) }, enabled = !busy && input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun Suggestion(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AiSettingsPanel() {
    var provider by remember { mutableStateOf(SettingsStore.aiProvider) }
    var endpoint by remember { mutableStateOf(SettingsStore.aiEndpoint) }
    var key by remember { mutableStateOf(SettingsStore.aiApiKey) }
    var model by remember { mutableStateOf(SettingsStore.aiModel) }
    var includePage by remember { mutableStateOf(SettingsStore.aiIncludePage) }
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxWidth().padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("AI Provider", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("OpenAI Compatible", "Anthropic", "Gemini", "Local Ollama").forEach { p ->
                TextButton(onClick = {
                    provider = p
                    when (p) {
                        "OpenAI Compatible" -> { endpoint = "https://api.openai.com/v1/chat/completions"; model = "gpt-4o-mini" }
                        "Anthropic" -> { endpoint = "https://api.anthropic.com/v1/messages"; model = "claude-3-5-sonnet-latest" }
                        "Gemini" -> { endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"; model = "gemini-2.5-flash" }
                        "Local Ollama" -> { endpoint = "http://127.0.0.1:11434/v1/chat/completions"; model = "llama3.2" }
                    }
                }) { Text(p) }
            }
        }
        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, label = { Text("Endpoint URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API key (stored encrypted)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Attach active page text on send", style = MaterialTheme.typography.bodySmall)
            Switch(checked = includePage, onCheckedChange = { includePage = it })
        }
        Button(onClick = {
            SettingsStore.aiProvider = provider
            SettingsStore.aiEndpoint = endpoint
            SettingsStore.aiApiKey = key
            SettingsStore.aiModel = model
            SettingsStore.aiIncludePage = includePage
        }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
}
