package com.sphinx.voiceagent.media

import android.app.Application
import android.util.Log
import com.oudmon.wifi.GlassesControl
import com.oudmon.wifi.bean.GlassAlbumEntity
import java.io.File

// ─────────────────────────────────────────────────────────────────
// SyncManager — đồng bộ media từ kính về Android qua Wi-Fi
// ─────────────────────────────────────────────────────────────────
class SyncManager(private val context: Application) {

    companion object {
        private const val TAG = "CyanSyncManager"
    }

    // ── Event callbacks cho UI ────────────────────────────────────
    /** Gọi mỗi khi bắt đầu tải một file: index (1-based) / total */
    var onFileProgress: ((fileName: String, progress: Int) -> Unit)? = null

    /** Gọi khi tải xong từng file */
    var onFileSuccess: ((entity: GlassAlbumEntity) -> Unit)? = null

    /** Gọi khi toàn bộ quá trình hoàn thành */
    var onSyncComplete: (() -> Unit)? = null

    /** Gọi khi có lỗi */
    var onSyncError: ((fileType: Int, errorType: Int) -> Unit)? = null

    /** Gọi mỗi khi nhận được tốc độ Wi-Fi */
    var onWifiSpeed: ((speed: String) -> Unit)? = null

    // ─────────────────────────────────────────────────────────────
    // Khởi tạo service đồng bộ — gọi 1 lần khi app start
    // (MainActivity đã gọi initGlasses; SyncManager đăng ký listener)
    // ─────────────────────────────────────────────────────────────
    fun initWifiSyncService(albumDirectory: File) {
        val glasses = GlassesControl.getInstance(context)

        // Đảm bảo thư mục lưu trữ tồn tại
        if (!albumDirectory.exists()) albumDirectory.mkdirs()

        // Cấu hình đường dẫn lưu trữ
        glasses?.initGlasses(albumDirectory.absolutePath)

        // Đăng ký listener nhận dữ liệu từ kính qua Wi-Fi
        glasses?.setWifiDownloadListener(object : GlassesControl.WifiFilesDownloadListener {

            override fun fileCount(index: Int, total: Int) {
                Log.i(TAG, "Tiến trình đồng bộ: file $index / $total")
            }

            override fun fileProgress(fileName: String, progress: Int) {
                Log.i(TAG, "Đang tải: $fileName — $progress%")
                onFileProgress?.invoke(fileName, progress)
            }

            override fun fileWasDownloadSuccessfully(entity: GlassAlbumEntity) {
                Log.i(TAG, "Tải thành công: ${entity.fileName} → ${entity.filePath}")
                onFileSuccess?.invoke(entity)
            }

            override fun fileDownloadComplete() {
                Log.i(TAG, "✅ Đồng bộ hoàn tất — tất cả file đã tải về máy!")
                onSyncComplete?.invoke()
            }

            override fun fileDownloadError(fileType: Int, errorType: Int) {
                Log.e(TAG, "❌ Lỗi tải file — loại=$fileType mã=$errorType")
                onSyncError?.invoke(fileType, errorType)
            }

            override fun wifiSpeed(wifiSpeed: String) {
                Log.i(TAG, "Wi-Fi speed: $wifiSpeed")
                onWifiSpeed?.invoke(wifiSpeed)
            }

            // Luồng âm thanh PCM thời gian thực (dùng cho voice pipeline)
            override fun voiceFromGlassesStatus(status: Int) {
                Log.d(TAG, "voiceFromGlassesStatus=$status")
            }

            override fun voiceFromGlasses(pcmData: ByteArray) {
                // PCM được xử lý ở VoiceChatController — không làm gì ở đây
            }

            // EIS (anti-shake) callbacks
            override fun eisEnd(fileName: String, filePath: String) {}
            override fun eisError(fileName: String, sourcePath: String, errorInfo: String) {}

            // PCM recording conversion callbacks
            override fun recordingToPcm(fileName: String, filePath: String, duration: Int) {
                Log.i(TAG, "recordingToPcm: $fileName duration=${duration}ms → $filePath")
            }

            override fun recordingToPcmError(fileName: String, errorInfo: String) {
                Log.e(TAG, "recordingToPcmError: $fileName — $errorInfo")
            }

            override fun onGlassesControlSuccess() {
                Log.d(TAG, "GlassesControl command OK")
            }

            override fun onGlassesFail(errorCode: Int) {
                Log.e(TAG, "GlassesControl command FAILED — code=$errorCode")
            }
        })
    }

    // ─────────────────────────────────────────────────────────────
    // Kích hoạt đồng bộ: kéo toàn bộ media từ kính về thư mục local
    // ─────────────────────────────────────────────────────────────
    fun startSyncMedia() {
        Log.i(TAG, "Bắt đầu đồng bộ album từ kính…")
        GlassesControl.getInstance(context)?.importAlbum()
    }
}