package com.sphinx.voiceagent.voice

private const val DEFAULT_SESSION_ID = "accb819b-016c-4500-8701-3f1d70c1a8ab"
private const val DEFAULT_API_KEY = "vk_24e1e4130a130ec73250d99773b64e9d04549dd342b0d9de26ff79e1580d5a0d"
private const val AGENT_BASE_URL = "wss://api-agent.svisor.vn/ws/voice-call"

data class VoiceAgentConfig(
    val sessionId: String = DEFAULT_SESSION_ID,
    val apiKey: String = DEFAULT_API_KEY,
    val websocketUrl: String = "$AGENT_BASE_URL/$sessionId?api_key=$apiKey",
    val authToken: String? = null,
    val sampleRateHz: Int = 16_000,
    val playbackSampleRateHz: Int = 24_000,
    val channels: Int = 1,
    val encoding: String = "pcm_s16le",
    val locale: String = "vi-VN",
) {
    companion object {
        const val DEFAULT_WEBSOCKET_URL =
            "$AGENT_BASE_URL/$DEFAULT_SESSION_ID?api_key=$DEFAULT_API_KEY"
    }
}
