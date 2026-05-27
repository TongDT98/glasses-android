package com.sphinx.voiceagent.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import java.util.Locale

class VoiceResponsePlayer(
    private val context: Context,
    private val sampleRateHz: Int,
    private val eventSink: (VoiceChatEvent) -> Unit,
) : TextToSpeech.OnInitListener {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioTrack: AudioTrack? = null
    private var textToSpeech: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ttsReady = false

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            textToSpeech?.language = Locale("vi", "VN")
            textToSpeech?.setAudioAttributes(responseAudioAttributes())
        }
        eventSink(VoiceChatEvent.Status("TTS ready=$ttsReady"))
    }

    fun start() {
        if (audioTrack != null) return
        logOutputDevices()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRateHz)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(responseAudioAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBufferSize)
            .build()
            .also { player ->
                val preferredDevice = findBluetoothOutputDevice()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredDevice != null) {
                    player.preferredDevice = preferredDevice
                    eventSink(VoiceChatEvent.Status("Audio output preferred: ${preferredDevice.productName}"))
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                    eventSink(VoiceChatEvent.Status("No Bluetooth output device found; using Android phone speaker"))
                }
                player.play()
            }
    }

    fun playPcm(pcmData: ByteArray) {
        val player = audioTrack ?: return
        val written = player.write(pcmData, 0, pcmData.size)
        Log.d(TAG, "PCM response play bytes=${pcmData.size} written=$written")
    }

    fun speakFallback() {
        speak("xin chào")
    }

    fun playTestTone() {
        start()
        val durationMs = 700
        val samples = sampleRateHz * durationMs / 1_000
        val data = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = (sin(2.0 * PI * 880.0 * i / sampleRateHz) * Short.MAX_VALUE * 0.25).toInt()
            data[i * 2] = (value and 0xff).toByte()
            data[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        playPcm(data)
        eventSink(VoiceChatEvent.Status("Played diagnostic tone bytes=${data.size}"))
    }

    fun speak(text: String) {
        val tts = textToSpeech
        if (!ttsReady || tts == null) {
            eventSink(VoiceChatEvent.Status("TTS is not ready"))
            return
        }
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "voice_response_test")
        }
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, params, "voice_response_test")
        eventSink(VoiceChatEvent.Status("Speaking fallback/test: $text result=$result"))
    }

    fun stop() {
        audioTrack?.run {
            pause()
            flush()
            release()
        }
        audioTrack = null
        textToSpeech?.stop()
    }

    fun release() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
    }

    private fun responseAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()
    }

    private fun findBluetoothOutputDevice(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
    }
    private fun findBluetoothOutputDevice1(): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Ưu tiên BLE Headset trước (kính BLE audio, Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?.let { return it }
        }
        // Sau đó A2DP
        outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?.let { return it }
        // Sau đó SCO
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    private fun logOutputDevices() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (devices.isEmpty()) {
            eventSink(VoiceChatEvent.Status("Audio outputs: none"))
            return
        }
        eventSink(
            VoiceChatEvent.Status(
                "Media volume=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
                    "${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
            )
        )
        devices.forEach { device ->
            val message = "Audio output type=${device.type} name=${device.productName}"
            Log.d(TAG, message)
            eventSink(VoiceChatEvent.Status(message))
        }
    }

    companion object {
        private const val TAG = "VoiceResponsePlayer"
    }
}
