package com.tensorix.antigravityplayer.ai

class AiOrchestrator(private val keyManager: AiKeyManager) {
    private val agent = MusicAiAgent(keyManager)

    suspend fun handleVoiceText(text: String): AgentAction {
        return agent.processUserPrompt(text)
    }
}
