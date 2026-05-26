package com.shinxjsc.voiceagent.voice

data class VoiceAgentConfig(
    val websocketUrl: String,
    val authToken: String? = null,
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val encoding: String = "pcm_s16le",
    val locale: String = "vi-VN",
)
