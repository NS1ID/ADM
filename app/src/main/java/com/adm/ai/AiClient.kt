package com.adm.ai

import com.adm.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiMessage(val role: String, val content: String)

/**
 * Provider-agnostic AI client. Reads its configuration from [SettingsStore] so the
 * Settings screen is the single source of truth. Supports OpenAI-compatible endpoints
 * (OpenAI, OpenRouter, Ollama, LM Studio), Anthropic and Gemini.
 */
object AiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun ask(
        prompt: String,
        pageTitle: String?,
        pageUrl: String?,
        pageText: String?,
        history: List<AiMessage>,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = SettingsStore.aiEndpoint.trim()
            val key = SettingsStore.aiApiKey
            val model = SettingsStore.aiModel.trim().ifEmpty { "gpt-4o-mini" }
            val provider = SettingsStore.aiProvider
            val system = SettingsStore.aiSystemPrompt.trim().ifEmpty { DEFAULT_SYSTEM }

            require(endpoint.isNotBlank()) { "AI endpoint is empty — open Settings › AI to set it." }
            if (key.isBlank() && !isLocal(endpoint)) {
                error("API key is empty — open Settings › AI to add it (or use a local endpoint).")
            }

            val contextPrefix = buildString {
                if (SettingsStore.aiIncludePage && !pageUrl.isNullOrBlank() && pageUrl != "about:blank") {
                    append("[Active page] ").append(pageTitle ?: "Untitled").append('\n')
                    append("URL: ").append(pageUrl).append('\n')
                    if (!pageText.isNullOrBlank()) {
                        append("Content: ").append(pageText.take(8000)).append('\n')
                    }
                    append("---\n")
                }
            }
            val fullPrompt = contextPrefix + prompt

            when {
                provider.contains("Anthropic", true) || endpoint.contains("anthropic") ->
                    anthropic(endpoint, key, model, system, history, fullPrompt)
                provider.contains("Gemini", true) || endpoint.contains("generativelanguage") ->
                    gemini(endpoint, key, model, system, history, fullPrompt)
                else -> openAiCompatible(endpoint, key, model, system, history, fullPrompt)
            }
        }
    }

    private fun isLocal(url: String) =
        listOf("localhost", "127.0.0.1", "10.0.2.2", "192.168.").any { url.contains(it) }

    private fun openAiCompatible(
        endpoint: String, key: String, model: String, system: String,
        history: List<AiMessage>, prompt: String,
    ): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        history.takeLast(10).forEach {
            messages.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.3)

        val builder = Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
        if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
        return execute(builder.build()) { json ->
            json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }

    private fun anthropic(
        endpoint: String, key: String, model: String, system: String,
        history: List<AiMessage>, prompt: String,
    ): String {
        val messages = JSONArray()
        history.takeLast(10).forEach {
            messages.put(JSONObject().put("role", it.role).put("content", it.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", model.ifEmpty { "claude-3-5-sonnet-latest" })
            .put("max_tokens", 1500)
            .put("system", system)
            .put("messages", messages)

        val request = Request.Builder().url(endpoint)
            .post(body.toString().toRequestBody(JSON))
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .build()
        return execute(request) { json ->
            json.getJSONArray("content").getJSONObject(0).getString("text")
        }
    }

    private fun gemini(
        endpoint: String, key: String, model: String, system: String,
        history: List<AiMessage>, prompt: String,
    ): String {
        val url = if (endpoint.contains(":generateContent")) {
            if (key.isNotBlank() && !endpoint.contains("key=")) "$endpoint?key=$key" else endpoint
        } else {
            "https://generativelanguage.googleapis.com/v1beta/models/${model.ifEmpty { "gemini-2.5-flash" }}:generateContent?key=$key"
        }
        val parts = JSONArray().put(JSONObject().put("text", "$system\n\n$prompt"))
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        val body = JSONObject().put("contents", contents)
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        return execute(request) { json ->
            json.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        }
    }

    private fun execute(request: Request, extract: (JSONObject) -> String): String {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Provider error ${response.code}: ${raw.take(200)}")
            return extract(JSONObject(raw)).trim()
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val DEFAULT_SYSTEM =
        "You are a concise, accurate assistant embedded in the ADM+ browser. Answer clearly and briefly."
}
