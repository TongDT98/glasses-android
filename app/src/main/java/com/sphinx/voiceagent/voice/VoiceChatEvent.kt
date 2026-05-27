package com.sphinx.voiceagent.voice

sealed class VoiceChatEvent {
    object Ready : VoiceChatEvent()
    object Disconnected : VoiceChatEvent()
    data class Status(val message: String) : VoiceChatEvent()
    data class AgentText(val message: String) : VoiceChatEvent()
    data class Error(val message: String, val cause: Throwable? = null) : VoiceChatEvent()
    /*data class Transcript(val text: String) : VoiceChatEvent()  // thêm vào
    data class AgentToken(val token: String) : VoiceChatEvent() // thêm vào*/
}
