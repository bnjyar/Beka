package dev.beka.llm

import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

interface LlmProvider {
    val config: ProviderConfig
    suspend fun listModels(): List<ModelInfo>
    fun chat(request: ChatRequest): Flow<ChatEvent>
}

object Providers {
    fun create(config: ProviderConfig, apiKey: String?, client: OkHttpClient): LlmProvider =
        when (config.kind) {
            ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatProvider(config, apiKey, client)
            ProviderKind.ANTHROPIC_COMPATIBLE -> AnthropicProvider(config, apiKey, client)
        }
}

data class Preset(val name: String, val kind: ProviderKind, val baseUrl: String, val model: String)

object Presets {
    val all = listOf(
        Preset("OpenRouter", ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", ""),
        Preset("OpenAI", ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", ""),
        Preset("Anthropic", ProviderKind.ANTHROPIC_COMPATIBLE, "https://api.anthropic.com", ""),
        Preset("Gemini", ProviderKind.OPENAI_COMPATIBLE, "https://generativelanguage.googleapis.com/v1beta/openai", ""),
        Preset("Ollama (LAN)", ProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.10:11434/v1", ""),
        Preset("Custom OpenAI-compatible", ProviderKind.OPENAI_COMPATIBLE, "", ""),
        Preset("Custom Anthropic-compatible", ProviderKind.ANTHROPIC_COMPATIBLE, "", ""),
    )
}
