package com.shinxjsc.voiceagent.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

class PcmAudioPlayer(
    private val sampleRateHz: Int = 16_000,
) {
    private var audioTrack: AudioTrack? = null

    fun start() {
        if (audioTrack != null) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRateHz)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build()
            )
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
            .also { it.play() }
    }

    fun play(pcmData: ByteArray) {
        val player = audioTrack ?: return
        player.write(pcmData, 0, pcmData.size)
    }

    fun stop() {
        audioTrack?.run {
            pause()
            flush()
            release()
        }
        audioTrack = null
    }
}
