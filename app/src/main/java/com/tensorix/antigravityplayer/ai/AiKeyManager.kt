package com.tensorix.antigravityplayer.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AiProvider { GEMINI, OPENAI, CLAUDE, GROQ }

class AiKeyManager(context: Context) {

    companion object {
        val AVAILABLE_MODELS: Map<AiProvider, List<String>> = mapOf(
            AiProvider.GEMINI to listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash-exp"),
            AiProvider.OPENAI to listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo"),
            AiProvider.CLAUDE to listOf("claude-3-5-sonnet-20241022", "claude-3-haiku-20240307"),
            AiProvider.GROQ to listOf("llama-3.3-70b-versatile", "mixtral-8x7b-32768", "gemma2-9b-it")
        )

        private const val DEFAULT_MODEL = "gemini-1.5-flash"
    }

    val availableModels: Map<AiProvider, List<String>>
        get() = AVAILABLE_MODELS

    private val prefs: SharedPreferences = runCatching {
        context.applicationContext.getSharedPreferences("antigravity_ai_keys", Context.MODE_PRIVATE)
    }.getOrElse {
        context.getSharedPreferences("antigravity_ai_keys", Context.MODE_PRIVATE)
    }

    private val _selectedProvider: MutableStateFlow<AiProvider> = MutableStateFlow(
        runCatching {
            val savedName = prefs.getString("selected_provider", AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
            AiProvider.valueOf(savedName)
        }.getOrDefault(AiProvider.GEMINI)
    )
    val selectedProvider: StateFlow<AiProvider> = _selectedProvider.asStateFlow()

    private val _selectedModel: MutableStateFlow<String> = MutableStateFlow(
        runCatching {
            val provider = _selectedProvider.value
            val defaultModel = AVAILABLE_MODELS[provider]?.firstOrNull() ?: DEFAULT_MODEL
            prefs.getString("model_${provider.name}", defaultModel) ?: defaultModel
        }.getOrDefault(DEFAULT_MODEL)
    )
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    fun getApiKey(provider: AiProvider? = null): String {
        val targetProvider = provider ?: _selectedProvider.value
        return runCatching {
            prefs.getString("key_${targetProvider.name}", "") ?: ""
        }.getOrDefault("")
    }

    fun setApiKey(provider: AiProvider, key: String) {
        runCatching {
            prefs.edit().putString("key_${provider.name}", key.trim()).apply()
        }
    }

    fun setSelectedProvider(provider: AiProvider) {
        _selectedProvider.value = provider
        runCatching {
            prefs.edit().putString("selected_provider", provider.name).apply()
        }
        val newModel = getSelectedModelForProvider(provider)
        _selectedModel.value = newModel
    }

    fun getSelectedModelForProvider(provider: AiProvider? = null): String {
        val targetProvider = provider ?: _selectedProvider.value
        val defaultModel = AVAILABLE_MODELS[targetProvider]?.firstOrNull() ?: DEFAULT_MODEL
        return runCatching {
            prefs.getString("model_${targetProvider.name}", defaultModel) ?: defaultModel
        }.getOrDefault(defaultModel)
    }

    fun setSelectedModel(provider: AiProvider, model: String) {
        runCatching {
            prefs.edit().putString("model_${provider.name}", model).apply()
        }
        if (_selectedProvider.value == provider) {
            _selectedModel.value = model
        }
    }
}
