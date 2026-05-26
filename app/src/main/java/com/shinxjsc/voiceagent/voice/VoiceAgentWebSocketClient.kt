package com.shinxjsc.voiceagent.voice

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

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null

    fun connect(config: VoiceAgentConfig) {
        disconnect("reconnect")

        val requestBuilder = Request.Builder().url(config.websocketUrl)
        config.authToken
            ?.takeIf { it.isNotBlank() }
            ?.let { requestBuilder.header("Authorization", "Bearer $it") }

        webSocket = httpClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    callback.onOpen()
                    webSocket.send(
                        JSONObject()
                            .put("type", "session.start")
                            .put("sampleRateHz", config.sampleRateHz)
                            .put("channels", config.channels)
                            .put("encoding", config.encoding)
                            .put("locale", config.locale)
                            .toString()
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    callback.onText(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    callback.onAudio(bytes.toByteArray())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    callback.onClosed("closed $code $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    callback.onFailure(response?.message ?: t.message ?: "WebSocket failure", t)
                }
            }
        )
    }

    fun sendAudio(pcmData: ByteArray): Boolean {
        return webSocket?.send(ByteString.of(*pcmData)) == true
    }

    fun sendJson(type: String, payload: JSONObject = JSONObject()): Boolean {
        payload.put("type", type)
        return webSocket?.send(payload.toString()) == true
    }

    fun disconnect(reason: String = "client disconnect") {
        webSocket?.close(1000, reason)
        webSocket = null
    }
}
