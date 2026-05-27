package com.sphinx.voiceagent.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import android.util.Log

class VoiceChatController(
    context: Context,
    private val eventSink: (VoiceChatEvent) -> Unit,
) : VoiceAgentWebSocketClient.Callback {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webSocketClient = VoiceAgentWebSocketClient(this)
    private var responsePlayer = VoiceResponsePlayer(appContext, 24_000, ::emit)
    private var active = false
    private var glassesSpeaking = false
    private var pcmFrameCount = 0
    private var pcmByteCount = 0L
    private val speechChunks = ArrayList<ByteArray>()
    private var awaitingAgentResponse = false
    private var agentReady = false
    private var fallbackRunnable: Runnable? = null
    private var readyTimeoutRunnable: Runnable? = null
    private var tokenSpeakRunnable: Runnable? = null
    private val agentTextBuffer = StringBuilder()
    private var receivedAgentAudio = false

    fun start(config: VoiceAgentConfig) {
        if (active) stop()
        active = true
        glassesSpeaking = false
        pcmFrameCount = 0
        pcmByteCount = 0L
        speechChunks.clear()
        awaitingAgentResponse = false
        agentReady = false
        receivedAgentAudio = false
        agentTextBuffer.clear()
        cancelReadyTimeout()
        cancelTokenSpeak()
        responsePlayer.release()
        responsePlayer = VoiceResponsePlayer(
            appContext,
            config.playbackSampleRateHz,
            ::emit
        ).also { it.start() }
        emit(VoiceChatEvent.Status("Connecting voice agent: ${config.websocketUrl}"))
        webSocketClient.connect(config)
        scheduleReadyTimeout()
    }

    fun stop() {
        active = false
        glassesSpeaking = false
        pcmFrameCount = 0
        pcmByteCount = 0L
        speechChunks.clear()
        awaitingAgentResponse = false
        agentReady = false
        receivedAgentAudio = false
        agentTextBuffer.clear()
        cancelFallback()
        cancelReadyTimeout()
        cancelTokenSpeak()
        webSocketClient.disconnect()
        responsePlayer.stop()
        emit(VoiceChatEvent.Status("Voice chat stopped"))
    }

    fun onGlassesPcm(pcmData: ByteArray) {
        Log.d(TAG, "PCM callback bytes=${pcmData.size} active=$active speaking=$glassesSpeaking")
        if (!active) {
            emit(VoiceChatEvent.Status("PCM skipped: voice chat inactive, bytes=${pcmData.size}"))
            return
        }
        if (!agentReady) {
            emit(VoiceChatEvent.Status("PCM skipped: agent is not ready, bytes=${pcmData.size}"))
            return
        }
        if (!glassesSpeaking) {
            emit(VoiceChatEvent.Status("PCM skipped: glasses not speaking, bytes=${pcmData.size}"))
            return
        }
        if (pcmData.isEmpty()) {
            emit(VoiceChatEvent.Status("PCM skipped: empty frame"))
            return
        }
        pcmFrameCount++
        pcmByteCount += pcmData.size
        speechChunks.add(pcmData.copyOf())
        if (pcmFrameCount == 1 || pcmFrameCount % 20 == 0) {
            emit(VoiceChatEvent.Status("PCM buffered frames=$pcmFrameCount bytes=$pcmByteCount last=${pcmData.size}"))
        }
    }

    fun onGlassesVoiceStatus(status: Int) {
        when (status) {
            1 -> {
                glassesSpeaking = true
                pcmFrameCount = 0
                pcmByteCount = 0L
                speechChunks.clear()
                emit(VoiceChatEvent.Status("Glasses speaking started"))
            }
            2 -> {
                glassesSpeaking = false
                val speechAudio = drainSpeechAudio()
                if (speechAudio.isNotEmpty()) {
                    emit(VoiceChatEvent.Status("Sending utterance to agent bytes=${speechAudio.size} frames=$pcmFrameCount"))
                    if (!webSocketClient.sendAudio(speechAudio)) {
                        emit(VoiceChatEvent.Error("Cannot send utterance to voice agent"))
                        return
                    }
                    awaitingAgentResponse = true
                    scheduleFallback()
                }
                emit(VoiceChatEvent.Status("Glasses speaking ended frames=$pcmFrameCount bytes=$pcmByteCount"))
            }
            else -> emit(VoiceChatEvent.Status("Glasses voice status: $status"))
        }
    }

    override fun onOpen() {
        emit(VoiceChatEvent.Status("Voice agent socket opened, waiting for ready"))
    }

    override fun onText(message: String) {
        Log.d("messagevoice", message)
        try {
            val json = JSONObject(message)
            when (val type = json.optString("type")) {
                "ready" -> {
                    agentReady = true
                    cancelReadyTimeout()
                    emit(VoiceChatEvent.Ready)
                }
                "transcript" -> emit(VoiceChatEvent.Status("User: ${json.optString("text")}"))
                "token" -> {
                    val token = json.optString("text")
                    if (token.isNotBlank()) {
                        agentTextBuffer.append(token)
                        scheduleTokenSpeak()
                    }
                    emit(VoiceChatEvent.AgentText(token))
                }
                //"transcript" -> emit(VoiceChatEvent.Transcript(json.optString("text")))
                //"token"      -> emit(VoiceChatEvent.AgentToken(json.optString("text")))
                "agent_start" -> {
                    awaitingAgentResponse = false
                    receivedAgentAudio = false
                    agentTextBuffer.clear()
                    cancelFallback()
                    cancelTokenSpeak()
                    emit(VoiceChatEvent.Status("Agent started"))
                }
                "agent_done" -> {
                    speakAgentTextIfNoAudio()
                    emit(VoiceChatEvent.Status("Agent done"))
                }
                "error" -> emit(VoiceChatEvent.Error(json.optString("message", "Server error")))
                "end" -> {
                    active = false
                    glassesSpeaking = false
                    speechChunks.clear()
                    agentReady = false
                    receivedAgentAudio = false
                    agentTextBuffer.clear()
                    cancelReadyTimeout()
                    cancelTokenSpeak()
                    emit(VoiceChatEvent.Status("Voice session ended: ${json.optString("reason", "unknown")}"))
                    emit(VoiceChatEvent.Disconnected)
                }
                else -> emit(VoiceChatEvent.AgentText(if (type.isBlank()) message else "Unknown event: $message"))
            }
        } catch (e: Exception) {
            emit(VoiceChatEvent.AgentText(message))
        }
    }

    override fun onAudio(pcmData: ByteArray) {
        awaitingAgentResponse = false
        receivedAgentAudio = true
        cancelFallback()
        cancelTokenSpeak()
        responsePlayer.playPcm(pcmData)
    }

    override fun onClosed(reason: String) {
        active = false
        glassesSpeaking = false
        speechChunks.clear()
        awaitingAgentResponse = false
        agentReady = false
        receivedAgentAudio = false
        agentTextBuffer.clear()
        cancelFallback()
        cancelReadyTimeout()
        cancelTokenSpeak()
        emit(VoiceChatEvent.Status("Voice agent $reason"))
        emit(VoiceChatEvent.Disconnected)
    }

    override fun onFailure(message: String, throwable: Throwable?) {
        active = false
        glassesSpeaking = false
        speechChunks.clear()
        awaitingAgentResponse = false
        agentReady = false
        receivedAgentAudio = false
        agentTextBuffer.clear()
        cancelFallback()
        cancelReadyTimeout()
        cancelTokenSpeak()
        emit(VoiceChatEvent.Error(message, throwable))
        emit(VoiceChatEvent.Disconnected)
    }

    fun testSpeaker() {
        responsePlayer.start()
        responsePlayer.playTestTone()
        responsePlayer.speakFallback()
    }

    fun release() {
        cancelFallback()
        cancelReadyTimeout()
        cancelTokenSpeak()
        responsePlayer.release()
    }

    private fun scheduleFallback() {
        cancelFallback()
        val runnable = Runnable {
            if (active && awaitingAgentResponse) {
                awaitingAgentResponse = false
                emit(VoiceChatEvent.Status("No agent response audio; playing fallback"))
                responsePlayer.speakFallback()
            }
        }
        fallbackRunnable = runnable
        mainHandler.postDelayed(runnable, AGENT_AUDIO_FALLBACK_MS)
    }

    private fun cancelFallback() {
        fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        fallbackRunnable = null
    }

    private fun scheduleTokenSpeak() {
        cancelTokenSpeak()
        val runnable = Runnable { speakAgentTextIfNoAudio() }
        tokenSpeakRunnable = runnable
        mainHandler.postDelayed(runnable, AGENT_TOKEN_SPEAK_DEBOUNCE_MS)
    }

    private fun cancelTokenSpeak() {
        tokenSpeakRunnable?.let { mainHandler.removeCallbacks(it) }
        tokenSpeakRunnable = null
    }

    private fun speakAgentTextIfNoAudio() {
        cancelTokenSpeak()
        if (receivedAgentAudio) return
        val text = agentTextBuffer.toString().trim()
        if (text.isBlank()) return
        awaitingAgentResponse = false
        cancelFallback()
        emit(VoiceChatEvent.Status("No agent audio received; speaking token text"))
        responsePlayer.speak(text)
        agentTextBuffer.clear()
    }

    private fun scheduleReadyTimeout() {
        cancelReadyTimeout()
        val runnable = Runnable {
            if (active && !agentReady) {
                emit(VoiceChatEvent.Error("Voice agent connected but did not send ready within ${AGENT_READY_TIMEOUT_MS}ms"))
            }
        }
        readyTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, AGENT_READY_TIMEOUT_MS)
    }

    private fun cancelReadyTimeout() {
        readyTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        readyTimeoutRunnable = null
    }

    private fun drainSpeechAudio(): ByteArray {
        if (speechChunks.isEmpty()) return ByteArray(0)
        val totalBytes = speechChunks.sumOf { it.size }
        val combined = ByteArray(totalBytes)
        var offset = 0
        speechChunks.forEach { chunk ->
            chunk.copyInto(combined, offset)
            offset += chunk.size
        }
        speechChunks.clear()
        return combined
    }

    private fun emit(event: VoiceChatEvent) {
        mainHandler.post { eventSink(event) }
    }

    companion object {
        private const val TAG = "VoiceChatController"
        private const val AGENT_AUDIO_FALLBACK_MS = 4_000L
        private const val AGENT_READY_TIMEOUT_MS = 8_000L
        private const val AGENT_TOKEN_SPEAK_DEBOUNCE_MS = 1_200L
    }
}
