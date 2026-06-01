package com.sphinx.voiceagent.media

import android.util.Log
import com.oudmon.ble.base.communication.ILargeDataResponse
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse

// ─────────────────────────────────────────────────────────────────
// Command bytes — HeyCyan SDK docs
// ─────────────────────────────────────────────────────────────────
object CyanCommand {
    val TAKE_PHOTO   = byteArrayOf(0x02, 0x01, 0x01)
    val VIDEO_START  = byteArrayOf(0x02, 0x01, 0x02)
    val VIDEO_STOP   = byteArrayOf(0x02, 0x01, 0x03)
    val AUDIO_START  = byteArrayOf(0x02, 0x01, 0x08)
    val AUDIO_STOP   = byteArrayOf(0x02, 0x01, 0x0C)
    val GET_UNSYNCED = byteArrayOf(0x02, 0x04)
}

// ─────────────────────────────────────────────────────────────────
// MediaManager
// ─────────────────────────────────────────────────────────────────
class MediaManager {

    companion object {
        private const val TAG = "CyanMediaManager"
    }

    var isRecordingVideo = false
        private set

    var isRecordingAudio = false
        private set

    interface MediaResultCallback {
        fun onSuccess(message: String)
        fun onError(errorCode: Int, message: String)
    }

    // ── Helper: build ILargeDataResponse từ lambda ────────────────
    // glassesControl() nhận ILargeDataResponse<GlassModelControlResponse>
    // — một interface với parseData(cmdType: Int, rsp: GlassModelControlResponse)
    // Kotlin không tự SAM-convert vì generic → phải tạo object anonymous.
    private fun controlCallback(
        block: (cmdType: Int, rsp: GlassModelControlResponse) -> Unit
    ): ILargeDataResponse<GlassModelControlResponse> =
        object : ILargeDataResponse<GlassModelControlResponse> {
            override fun parseData(cmdType: Int, response: GlassModelControlResponse) {
                block(cmdType, response)
            }
        }

    // ─────────────────────────────────────────────────────────────
    // 1. CHỤP ẢNH  (0x02 0x01 0x01)
    //    dataType == 1 && errorCode == 0  →  thành công
    // ─────────────────────────────────────────────────────────────
    fun takePhoto(callback: MediaResultCallback) {
        Log.d(TAG, "Gửi lệnh chụp ảnh")
        LargeDataHandler.getInstance().glassesControl(
            CyanCommand.TAKE_PHOTO,
            controlCallback { _, rsp ->
                Log.d(TAG, "takePhoto → dataType=${rsp.dataType} errorCode=${rsp.errorCode} workType=${rsp.workTypeIng}")
                if (rsp.dataType == 1 && rsp.errorCode == 0) {
                    callback.onSuccess("📷 Kính đã chụp ảnh! Đang xử lý thumbnail…")
                } else {
                    callback.onError(rsp.errorCode, "Chụp ảnh thất bại: ${busyReason(rsp.workTypeIng)}")
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 2. QUAY VIDEO toggle  (0x02 0x01 0x02 / 0x03)
    // ─────────────────────────────────────────────────────────────
    fun toggleVideoRecording(callback: MediaResultCallback) {
        val starting = !isRecordingVideo
        val cmd = if (starting) CyanCommand.VIDEO_START else CyanCommand.VIDEO_STOP
        Log.d(TAG, "Video ${if (starting) "START" else "STOP"}")

        LargeDataHandler.getInstance().glassesControl(
            cmd,
            controlCallback { _, rsp ->
                Log.d(TAG, "toggleVideo → dataType=${rsp.dataType} errorCode=${rsp.errorCode} workType=${rsp.workTypeIng}")
                if (rsp.dataType == 1 && rsp.errorCode == 0) {
                    isRecordingVideo = starting
                    val msg = if (isRecordingVideo) "🎥 Kính đang quay video…" else "⏹ Đã dừng và lưu video."
                    callback.onSuccess(msg)
                } else {
                    callback.onError(rsp.errorCode, "Quay video thất bại: ${busyReason(rsp.workTypeIng)}")
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 3. GHI ÂM toggle  (0x02 0x01 0x08 / 0x0C)
    // ─────────────────────────────────────────────────────────────
    fun toggleAudioRecording(callback: MediaResultCallback) {
        val starting = !isRecordingAudio
        val cmd = if (starting) CyanCommand.AUDIO_START else CyanCommand.AUDIO_STOP
        Log.d(TAG, "Audio ${if (starting) "START" else "STOP"}")

        LargeDataHandler.getInstance().glassesControl(
            cmd,
            controlCallback { _, rsp ->
                Log.d(TAG, "toggleAudio → dataType=${rsp.dataType} errorCode=${rsp.errorCode} workType=${rsp.workTypeIng}")
                if (rsp.dataType == 1 && rsp.errorCode == 0) {
                    isRecordingAudio = starting
                    val msg = if (isRecordingAudio) "🎙 Kính đang ghi âm…" else "⏹ Đã dừng và lưu file âm thanh."
                    callback.onSuccess(msg)
                } else {
                    callback.onError(rsp.errorCode, "Ghi âm thất bại: ${busyReason(rsp.workTypeIng)}")
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 4. KIỂM TRA FILE CHƯA SYNC  (0x02 0x04)
    //    dataType == 4  →  có kết quả; đọc imageCount/videoCount/recordCount
    // ─────────────────────────────────────────────────────────────
    fun checkUnsyncedMedia(
        onChecked: (total: Int, image: Int, video: Int, audio: Int) -> Unit
    ) {
        LargeDataHandler.getInstance().glassesControl(
            CyanCommand.GET_UNSYNCED,
            controlCallback { _, rsp ->
                Log.d(TAG, "checkUnsynced → dataType=${rsp.dataType} img=${rsp.imageCount} vid=${rsp.videoCount} rec=${rsp.recordCount}")
                if (rsp.dataType == 4) {
                    val total = rsp.imageCount + rsp.videoCount + rsp.recordCount
                    onChecked(total, rsp.imageCount, rsp.videoCount, rsp.recordCount)
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Helper: dịch workTypeIng sang text
    // ─────────────────────────────────────────────────────────────
    private fun busyReason(workType: Int): String = when (workType) {
        2    -> "Kính đang ở chế độ chụp ảnh"
        4    -> "Kính đang truyền dữ liệu"
        5    -> "Kính đang giao tiếp AI"
        6    -> "Kính đang ghi âm"
        7    -> "Kính đang OTA firmware"
        8    -> "Kính đang quay video"
        else -> "Kính đang bận (workType=$workType)"
    }
}
