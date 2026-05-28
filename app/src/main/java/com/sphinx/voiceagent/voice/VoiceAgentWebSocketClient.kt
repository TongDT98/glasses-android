package com.sphinx.voiceagent.voice

import android.util.Base64
import android.util.Log
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VoiceAgentWebSocketClient(
    private val callback: Callback,
) {
    interface Callback {
        fun onOpen()
        fun onText(message: String)
        fun onAudio(pcmData: ByteArray)
        fun onClosed(reason: String)
        fun onFailure(message: String, throwable: Throwable?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!isConnected) return
            webSocket?.send(JSONObject().put("type", "ping").toString())
            Log.d(TAG, "Heartbeat ping sent")
            mainHandler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false

    fun connect(config: VoiceAgentConfig) {
        disconnect("reconnect")
        isConnected = false

        val requestBuilder = Request.Builder().url(config.websocketUrl)
        Log.d(TAG, "Connecting url=${config.websocketUrl}")
        config.authToken
            ?.takeIf { it.isNotBlank() }
            ?.let { requestBuilder.header("Authorization", "Bearer $it") }

        webSocket = httpClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected = true
                    Log.d(TAG, "WebSocket open code=${response.code} message=${response.message}")
                    startHeartbeat()
                    callback.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Text received: $text")
                    callback.onText(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Log.d(TAG, "Audio received bytes=${bytes.size}")
                    callback.onAudio(bytes.toByteArray())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected = false
                    stopHeartbeat()
                    Log.d(TAG, "WebSocket closed code=$code reason=$reason")
                    callback.onClosed("closed $code $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    stopHeartbeat()
                    Log.e(TAG, "WebSocket failure code=${response?.code} message=${response?.message}", t)
                    callback.onFailure(
                        "code=${response?.code} message=${response?.message ?: t.message ?: "WebSocket failure"}",
                        t
                    )
                }
            }
        )
    }

    fun sendAudio(pcmData: ByteArray): Boolean {
        if (!isConnected) {
            Log.w(TAG, "Skip audio send: socket is not connected, pcmBytes=${pcmData.size}")
            return false
        }
        val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val payload = JSONObject()
            .put(
                "audio_event",
                JSONObject().put("audio_base_64", base64Audio)
            )
        val sent = webSocket?.send(payload.toString()) == true
        Log.d(TAG, "Audio send sent=$sent pcmBytes=${pcmData.size} b64Chars=${base64Audio.length} queueBytes=${webSocket?.queueSize()}")
        return sent
    }

    fun sendJson(type: String, payload: JSONObject = JSONObject()): Boolean {
        if (!isConnected) return false
        payload.put("type", type)
        return webSocket?.send(payload.toString()) == true
    }

    fun disconnect(reason: String = "client disconnect") {
        isConnected = false
        stopHeartbeat()
        Log.d(TAG, "Disconnect reason=$reason")
        webSocket?.close(1000, reason)
        webSocket = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
    }

    private fun stopHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
    }

    companion object {
        private const val TAG = "VoiceAgentWebSocket"
        private const val HEARTBEAT_MS = 10_000L
    }
}
