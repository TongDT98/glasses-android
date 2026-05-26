package com.shinxjsc.voiceagent.voice

sealed class VoiceChatEvent {
    data class Status(val message: String) : VoiceChatEvent()
    data class AgentText(val message: String) : VoiceChatEvent()
    data class Error(val message: String, val cause: Throwable? = null) : VoiceChatEvent()
}
