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
    private var pcmFlushRunnable: Runnable? = null
    private val agentTextBuffer = StringBuilder()
    private var receivedAgentAudio = false
    private var currentConfig: VoiceAgentConfig? = null
    private val chunkBuffer = java.io.ByteArrayOutputStream()
    private val BUFFER_SIZE_THRESHOLD = 6400

    fun start(config: VoiceAgentConfig) {
        if (active) stop()
        currentConfig = config
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
        cancelPcmFlush()
        responsePlayer.release()
        responsePlayer = VoiceResponsePlayer(
            appContext,
            config.playbackSampleRateHz,
            ::emit
        ).also { it.start() }
        emit(VoiceChatEvent.Status("Connecting voice agent: ${config.websocketUrl}"))
        synchronized(chunkBuffer) { chunkBuffer.reset() }
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
        cancelPcmFlush()
        webSocketClient.disconnect()
        currentConfig = null
        //synchronized(chunkBuffer) { chunkBuffer.reset() }
        responsePlayer.stop()
        emit(VoiceChatEvent.Status("Voice chat stopped"))
    }

    fun onGlassesPcm(pcmData: ByteArray) {
        if (!active) return
        if (pcmData.isEmpty()) return

        synchronized(chunkBuffer) {
            chunkBuffer.write(pcmData)
            if (chunkBuffer.size() >= BUFFER_SIZE_THRESHOLD) {
                val bytesToSend = chunkBuffer.toByteArray()
                chunkBuffer.reset()

                // Gửi cục âm thanh tối ưu lên Server
                webSocketClient.sendAudio(bytesToSend)
            }
        }
    }
    fun onGlassesPcm1(pcmData: ByteArray) {
        if (!active) return
        if (pcmData.isEmpty()) return

        // Stream trực tiếp hoặc gom cụm nhỏ (Buffer 6400 bytes như bước trước) rồi gửi
        pcmFrameCount++
        pcmByteCount += pcmData.size

        // Tiến hành gửi dữ liệu câu hỏi mới của bạn lên Server
        if (!webSocketClient.sendAudio(pcmData)) {
            Log.e(TAG, "Cannot stream audio chunk to voice agent")
        }
    }
    /*fun onGlassesPcm(pcmData: ByteArray) {
        if (!active) return
        if (pcmData.isEmpty()) return

        // Tăng bộ đếm để log kiểm tra dữ liệu từ kính xuống ổn định không
        pcmFrameCount++
        pcmByteCount += pcmData.size
        if (pcmFrameCount == 1 || pcmFrameCount % 50 == 0) {
            emit(VoiceChatEvent.Status("Streaming PCM frames=$pcmFrameCount bytes=$pcmByteCount"))
        }

        // Đẩy THẲNG luồng PCM realtime lên WebSocket của Agent ngay lập tức
        if (!webSocketClient.sendAudio(pcmData)) {
            Log.e(TAG, "Failed to stream audio chunk to websocket")
        }
    }*/

    fun onGlassesVoiceStatus(status: Int) {
        when (status) {
            1 -> { // Người dùng gọi "Hey Cyan" và bắt đầu nói câu mới
                glassesSpeaking = true
                pcmFrameCount = 0
                pcmByteCount = 0L
                speechChunks.clear()

                // 1. Dọn dẹp hàng đợi âm thanh trên Android
                responsePlayer.stop()
                responsePlayer.start()

                // 2. Xóa các buffer text/audio nội bộ của lượt thoại cũ trên App
                agentTextBuffer.clear()
                awaitingAgentResponse = false
                receivedAgentAudio = false
                cancelFallback()
                cancelTokenSpeak()
                cancelPcmFlush()

                // 3. --- GỬI LỆNH BÁO CHO SERVER AI DỪNG NÓI ---
                // Thử gửi sự kiện ngắt (tùy thuộc vào tài liệu API của api-agent.svisor.vn, thường là "interrupt" hoặc "stop")
                webSocketClient.sendJson("interrupt")
                // -----------------------------------------------

                emit(VoiceChatEvent.Status("Glasses speaking started - Interrupted and cleared state"))
            }
            2 -> {
                glassesSpeaking = false
                sendBufferedUtterance("status_end")
                emit(VoiceChatEvent.Status("Glasses speaking ended frames=$pcmFrameCount bytes=$pcmByteCount"))
            }
            else -> emit(VoiceChatEvent.Status("Glasses voice status: $status"))
        }
    }
    fun onGlassesVoiceStatus1(status: Int) {
        when (status) {
            1 -> { // Người dùng bắt đầu nhấn nút / nói câu mới
                glassesSpeaking = true
                pcmFrameCount = 0
                pcmByteCount = 0L
                speechChunks.clear()

                // --- THÊM DÒNG NÀY ĐỂ NGẮT CÂU TRẢ LỜI CŨ CỦA AGENT NGAY LẬP TỨC ---
                responsePlayer.stop()
                responsePlayer.start() // Khởi động lại trình phát sạch cho lượt mới
                // ------------------------------------------------------------------

                emit(VoiceChatEvent.Status("Glasses speaking started - Interrupted previous response"))
            }
            2 -> {
                glassesSpeaking = false
                sendBufferedUtterance("status_end")
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
                        // --- ĐÃ BỎ: scheduleTokenSpeak() để tắt hoàn toàn giọng nói thứ 2 ---
                    }
                    // Chỉ emit để hiển thị chữ (Subtitles) lên giao diện người dùng
                    emit(VoiceChatEvent.AgentText(token))
                }

                "agent_start" -> {
                    awaitingAgentResponse = false
                    receivedAgentAudio = false
                    agentTextBuffer.clear()
                    cancelFallback()
                    cancelTokenSpeak()

                    // --- THÊM: Nếu Agent bắt đầu câu mới (hoặc bị ép ngắt), chặn đứng Audio cũ kẹt trong loa ---
                    responsePlayer.stop()
                    responsePlayer.start()

                    emit(VoiceChatEvent.Status("Agent started"))
                }

                "agent_done" -> {
                    // --- ĐÃ BỎ: speakAgentTextIfNoAudio() vì chúng ta hoàn toàn không dùng TTS local nữa ---
                    emit(VoiceChatEvent.Status("Agent done"))
                }

                "error" -> emit(VoiceChatEvent.Error(json.optString("message", "Server error")))

                "end" -> {
                    val reason = json.optString("reason", "unknown")
                    /*if (reason == "idle_timeout" && reconnectAgent("idle_timeout")) {
                        return
                    }*/
                    if (reason == "idle_timeout") {
                        emit(VoiceChatEvent.Status("Server sent idle_timeout, keeping player active until finished"))
                    }
                    active = false
                    glassesSpeaking = false
                    speechChunks.clear()
                    agentReady = false
                    receivedAgentAudio = false
                    agentTextBuffer.clear()
                    cancelReadyTimeout()
                    cancelTokenSpeak()
                    cancelPcmFlush()

                    // Giải phóng trình phát khi session kết thúc hoàn toàn
                   // responsePlayer.stop()
                    if (reason != "idle_timeout") {
                        responsePlayer.stop()
                    }

                    emit(VoiceChatEvent.Status("Voice session ended: $reason"))
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
        //if (active && reconnectAgent(reason)) return
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
        cancelPcmFlush()
        emit(VoiceChatEvent.Status("Voice agent $reason"))
        emit(VoiceChatEvent.Disconnected)
    }

    override fun onFailure(message: String, throwable: Throwable?) {
        //if (active && reconnectAgent(message)) return
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
        cancelPcmFlush()
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
        cancelPcmFlush()
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

    private fun schedulePcmFlush() {
        cancelPcmFlush()
        val runnable = Runnable {
            if (active && agentReady && speechChunks.isNotEmpty()) {
                glassesSpeaking = false
                sendBufferedUtterance("pcm_idle")
            }
        }
        pcmFlushRunnable = runnable
        mainHandler.postDelayed(runnable, PCM_IDLE_FLUSH_MS)
    }

    private fun cancelPcmFlush() {
        pcmFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        pcmFlushRunnable = null
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

    private fun reconnectAgent(reason: String): Boolean {
        val config = currentConfig ?: return false
        emit(VoiceChatEvent.Status("Reconnecting voice agent after: $reason"))
        glassesSpeaking = false
        speechChunks.clear()
        synchronized(chunkBuffer) { chunkBuffer.reset() }
        awaitingAgentResponse = false
        agentReady = false
        receivedAgentAudio = false
        agentTextBuffer.clear()
        cancelFallback()
        cancelReadyTimeout()
        cancelTokenSpeak()
        cancelPcmFlush()
        webSocketClient.connect(config)
        scheduleReadyTimeout()
        return true
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

    private fun sendBufferedUtterance(reason: String) {
        cancelPcmFlush()
        val frameCount = pcmFrameCount
        val speechAudio = drainSpeechAudio()
        if (speechAudio.isEmpty()) return
        emit(VoiceChatEvent.Status("Sending utterance to agent reason=$reason bytes=${speechAudio.size} frames=$frameCount"))
        if (!webSocketClient.sendAudio(speechAudio)) {
            emit(VoiceChatEvent.Error("Cannot send utterance to voice agent"))
            return
        }
        awaitingAgentResponse = true
        pcmFrameCount = 0
        pcmByteCount = 0L
        scheduleFallback()
    }

    private fun emit(event: VoiceChatEvent) {
        mainHandler.post { eventSink(event) }
    }

    companion object {
        private const val TAG = "VoiceChatController"
        private const val AGENT_AUDIO_FALLBACK_MS = 4_000L
        private const val AGENT_READY_TIMEOUT_MS = 8_000L
        private const val AGENT_TOKEN_SPEAK_DEBOUNCE_MS = 1_200L
        private const val PCM_IDLE_FLUSH_MS = 700L
    }
}
