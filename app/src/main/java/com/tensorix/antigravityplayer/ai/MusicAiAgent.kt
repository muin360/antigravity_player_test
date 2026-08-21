package com.tensorix.antigravityplayer.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class AgentAction {
    data class PlaySong(val query: String) : AgentAction()
    data class PlayMood(val mood: String) : AgentAction()
    data class SearchYoutube(val query: String) : AgentAction()
    data class DownloadYoutube(val query: String) : AgentAction()
    data class SetEqualizerPreset(val presetName: String) : AgentAction()
    data class SetSleepTimer(val minutes: Int) : AgentAction()
    data class PlaybackControl(val command: String) : AgentAction() // "pause", "next", "previous", "shuffle"
    data class ChatReply(val message: String) : AgentAction()
}

/**
 * Universal Agentic AI Engine supporting Gemini, OpenAI, Claude, and Groq with dynamic chatmodel selection.
 */
class MusicAiAgent(private val keyManager: AiKeyManager) {

    suspend fun processUserPrompt(prompt: String): AgentAction = withContext(Dispatchers.IO) {
        val provider = keyManager.selectedProvider.value
        val apiKey = keyManager.getApiKey(provider)
        val model = keyManager.selectedModel.value

        val lowerPrompt = prompt.lowercase().trim()

        // Rule-based instant pattern matching for high-speed offline control
        when {
            lowerPrompt == "pause" || lowerPrompt == "stop" || lowerPrompt == "play" || lowerPrompt == "resume" -> {
                return@withContext AgentAction.PlaybackControl(lowerPrompt)
            }
            lowerPrompt == "next" || lowerPrompt == "skip" -> {
                return@withContext AgentAction.PlaybackControl("next")
            }
            lowerPrompt == "previous" || lowerPrompt == "prev" || lowerPrompt == "back" -> {
                return@withContext AgentAction.PlaybackControl("previous")
            }
            lowerPrompt == "shuffle" || lowerPrompt == "shuffle on" || lowerPrompt == "shuffle off" -> {
                return@withContext AgentAction.PlaybackControl("shuffle")
            }
            lowerPrompt.contains("sleep timer") || lowerPrompt.matches(Regex(".*timer\\s*\\d+.*")) -> {
                val digits = Regex("\\d+").find(lowerPrompt)?.value?.toIntOrNull() ?: 30
                return@withContext AgentAction.SetSleepTimer(digits)
            }
            lowerPrompt.startsWith("download ") || (lowerPrompt.startsWith("save ") && lowerPrompt.contains("youtube")) -> {
                val query = lowerPrompt
                    .removePrefix("download ")
                    .removePrefix("save ")
                    .replace("from youtube", "")
                    .replace("youtube", "")
                    .trim()
                return@withContext AgentAction.DownloadYoutube(query)
            }
            lowerPrompt.startsWith("play rock") || lowerPrompt.contains("rock music") -> {
                return@withContext AgentAction.PlayMood("rock")
            }
            lowerPrompt.startsWith("play sad") || lowerPrompt.contains("sad songs") -> {
                return@withContext AgentAction.PlayMood("sad")
            }
            lowerPrompt.startsWith("play chill") || lowerPrompt.contains("chill music") -> {
                return@withContext AgentAction.PlayMood("chill")
            }
            lowerPrompt.startsWith("play workout") || lowerPrompt.contains("energetic") -> {
                return@withContext AgentAction.PlayMood("workout")
            }
            lowerPrompt.startsWith("bass boost") || lowerPrompt.contains("boost bass") -> {
                return@withContext AgentAction.SetEqualizerPreset("Bass Boost")
            }
            lowerPrompt.contains("flat eq") || lowerPrompt.contains("reset equalizer") -> {
                return@withContext AgentAction.SetEqualizerPreset("Flat")
            }
        }

        // If user has provided an API key for the selected provider, execute dynamic LLM call
        if (apiKey.isNotBlank()) {
            val aiResponse = when (provider) {
                AiProvider.GEMINI -> callGeminiApi(prompt, apiKey, model)
                AiProvider.OPENAI -> callOpenAiApi(prompt, apiKey, model)
                AiProvider.CLAUDE -> callClaudeApi(prompt, apiKey, model)
                AiProvider.GROQ -> callGroqApi(prompt, apiKey, model)
            }
            if (aiResponse != null) {
                return@withContext parseAgentResponse(aiResponse, prompt)
            }
        }

        // Fallback agentic response
        return@withContext when {
            lowerPrompt.startsWith("play ") -> AgentAction.PlaySong(lowerPrompt.removePrefix("play ").trim())
            lowerPrompt.startsWith("search ") -> AgentAction.SearchYoutube(lowerPrompt.removePrefix("search ").trim())
            lowerPrompt.startsWith("download ") -> AgentAction.DownloadYoutube(lowerPrompt.removePrefix("download ").trim())
            else -> AgentAction.ChatReply("I'm your Antigravity AI Music Assistant! Configure your ${provider.name} API key & model in settings to unlock full natural language tool orchestration.")
        }
    }

    private val systemPrompt = """
        You are Antigravity Music AI Assistant. Respond ONLY in JSON format with fields:
        action: one of 'PLAY_SONG', 'PLAY_MOOD', 'SEARCH_YOUTUBE', 'DOWNLOAD_YOUTUBE', 'SET_EQ', 'SET_TIMER', 'PLAYBACK_CONTROL', 'CHAT'
        target: the song name/query/preset name/minutes/control command
        replyMessage: short conversational text for the user
    """.trimIndent()

    private fun callGeminiApi(prompt: String, apiKey: String, model: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "$systemPrompt\n\nUser query: $prompt")
                    }))
                }))
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun callOpenAiApi(prompt: String, apiKey: String, model: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.openai.com/v1/chat/completions")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                root.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun callClaudeApi(prompt: String, apiKey: String, model: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.anthropic.com/v1/messages")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.doOutput = true

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("max_tokens", 300)
                put("system", systemPrompt)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                root.getJSONArray("content")
                    .getJSONObject(0)
                    .getString("text")
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun callGroqApi(prompt: String, apiKey: String, model: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("https://api.groq.com/openai/v1/chat/completions")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                root.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseAgentResponse(jsonText: String, originalPrompt: String): AgentAction {
        return try {
            val firstBrace = jsonText.indexOf('{')
            val lastBrace = jsonText.lastIndexOf('}')
            if (firstBrace != -1 && lastBrace > firstBrace) {
                val cleanJson = jsonText.substring(firstBrace, lastBrace + 1)
                val root = JSONObject(cleanJson)
                val action = root.optString("action", "CHAT").uppercase()
                val target = root.optString("target", "")
                val replyMessage = root.optString("replyMessage", "Action executed.")

                when (action) {
                    "PLAY_SONG" -> AgentAction.PlaySong(target.ifBlank { originalPrompt })
                    "PLAY_MOOD" -> AgentAction.PlayMood(target.ifBlank { "chill" })
                    "SEARCH_YOUTUBE" -> AgentAction.SearchYoutube(target.ifBlank { originalPrompt })
                    "DOWNLOAD_YOUTUBE" -> AgentAction.DownloadYoutube(target.ifBlank { originalPrompt })
                    "SET_EQ", "SET_EQUALIZER" -> AgentAction.SetEqualizerPreset(target.ifBlank { "Flat" })
                    "SET_TIMER", "SET_SLEEP_TIMER" -> AgentAction.SetSleepTimer(target.toIntOrNull() ?: 30)
                    "PLAYBACK_CONTROL" -> AgentAction.PlaybackControl(target.ifBlank { "play" })
                    else -> AgentAction.ChatReply(replyMessage)
                }
            } else {
                AgentAction.ChatReply(jsonText.take(200))
            }
        } catch (e: Exception) {
            AgentAction.ChatReply(jsonText.take(200))
        }
    }
}
