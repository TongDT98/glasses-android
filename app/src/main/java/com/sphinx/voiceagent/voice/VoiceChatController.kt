package com.sphinx.voiceagent.voice

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import android.util.Log

class VoiceChatController(
    private val eventSink: (VoiceChatEvent) -> Unit,
) : VoiceAgentWebSocketClient.Callback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webSocketClient = VoiceAgentWebSocketClient(this)
    private var audioPlayer = PcmAudioPlayer()
    private var active = false

    fun start(config: VoiceAgentConfig) {
        if (active) stop()
        active = true
        audioPlayer = PcmAudioPlayer(config.playbackSampleRateHz).also { it.start() }
        emit(VoiceChatEvent.Status("Connecting voice agent..."))
        webSocketClient.connect(config)
    }

    fun stop() {
        active = false
        webSocketClient.disconnect()
        audioPlayer.stop()
        emit(VoiceChatEvent.Status("Voice chat stopped"))
    }

    fun onGlassesPcm(pcmData: ByteArray) {
        if (!active) return
        if (!webSocketClient.sendAudio(pcmData)) {
            emit(VoiceChatEvent.Error("Cannot send audio frame to voice agent"))
        }
    }

    fun onGlassesVoiceStatus(status: Int) {
        emit(VoiceChatEvent.Status("Glasses voice status: $status"))
        if (active) {
            webSocketClient.sendJson(
                "glasses.voice_status",
                JSONObject().put("status", status)
            )
        }
    }

    override fun onOpen() {
        emit(VoiceChatEvent.Status("Voice agent connected"))
    }

    override fun onText(message: String) {
        Log.d("messagevoice", message)
        try {
            val json = JSONObject(message)
            when (val type = json.optString("type")) {
                "ready" -> emit(VoiceChatEvent.Status("Voice agent ready"))
                "transcript" -> emit(VoiceChatEvent.Status("User: ${json.optString("text")}"))
                "token" -> emit(VoiceChatEvent.AgentText(json.optString("text")))
                "agent_start" -> emit(VoiceChatEvent.Status("Agent started"))
                "agent_done" -> emit(VoiceChatEvent.Status("Agent done"))
                "error" -> emit(VoiceChatEvent.Error(json.optString("message", "Server error")))
                "end" -> {
                    active = false
                    emit(VoiceChatEvent.Status("Voice session ended: ${json.optString("reason", "unknown")}"))
                }
                else -> emit(VoiceChatEvent.AgentText(if (type.isBlank()) message else "Unknown event: $message"))
            }
        } catch (e: Exception) {
            emit(VoiceChatEvent.AgentText(message))
        }
    }

    override fun onAudio(pcmData: ByteArray) {
        audioPlayer.play(pcmData)
    }

    override fun onClosed(reason: String) {
        active = false
        emit(VoiceChatEvent.Status("Voice agent $reason"))
    }

    override fun onFailure(message: String, throwable: Throwable?) {
        active = false
        emit(VoiceChatEvent.Error(message, throwable))
    }

    private fun emit(event: VoiceChatEvent) {
        mainHandler.post { eventSink(event) }
    }
}
