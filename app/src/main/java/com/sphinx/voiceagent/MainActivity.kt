package com.sphinx.voiceagent
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.ILargeDataResponse
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
import com.oudmon.wifi.GlassesControl
import com.oudmon.wifi.bean.GlassAlbumEntity
import com.sphinx.voiceagent.databinding.ActivityMainBinding
import com.sphinx.voiceagent.media.MediaManager
import com.sphinx.voiceagent.media.SyncManager
import com.sphinx.voiceagent.ui.BluetoothUtils
import com.sphinx.voiceagent.ui.DeviceBindActivity
import com.sphinx.voiceagent.ui.MyApplication
import com.sphinx.voiceagent.ui.hasBluetooth
import com.sphinx.voiceagent.ui.requestBluetoothPermission
import com.sphinx.voiceagent.ui.requestLocationPermission
import com.sphinx.voiceagent.ui.startKtxActivity
import com.sphinx.voiceagent.voice.VoiceAgentConfig
import com.sphinx.voiceagent.voice.VoiceChatController
import com.sphinx.voiceagent.voice.VoiceChatEvent
import com.sphinx.voiceagent.media.MediaGalleryActivity
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.lifecycle.lifecycleScope
import com.sphinx.voiceagent.data.model.agent.response.CollectionResponse
import com.sphinx.voiceagent.data.repository.AgentRepository
import kotlinx.coroutines.launch
import com.sphinx.voiceagent.voice.VoiceSettingsActivity
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceChatController: VoiceChatController
    private val deviceNotifyListener = DeviceNotifyListener()
    private var microphoneCapturing = false
    private val mediaManager = MediaManager()
    private lateinit var syncManager: SyncManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun controlCallback(
        block: (cmdType: Int, rsp: GlassModelControlResponse) -> Unit
    ): ILargeDataResponse<GlassModelControlResponse> =
        object : ILargeDataResponse<GlassModelControlResponse> {
            override fun parseData(cmdType: Int, response: GlassModelControlResponse) =
                block(cmdType, response)
        }
    private  val agentRepo = AgentRepository()
    private var voiceCollections: List<CollectionResponse> = emptyList()
    private var currentCollectionId: String? = null
    private var selectedCollectionId: String? = null
    private val DOUBLE_CLICK_MS = 400L

    // Nút trái
    private var leftClickCount = 0
    private var leftPendingRunnable: Runnable? = null

    // Nút phải
    private var rightClickCount = 0
    private var rightPendingRunnable: Runnable? = null

    // Chặn spam: chỉ cho phép gửi lệnh sau khi lệnh trước đã về callback
    private var photoInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceChatController = VoiceChatController(this, ::renderVoiceEvent)
        syncManager = SyncManager(MyApplication.getInstance())
        initGlassesSdk()
        bindViews()
        loadVoiceSelector()
        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        ensureBluetoothEnabled()
        refreshDeviceCard()
    }

    override fun onDestroy() {
        voiceChatController.stop()
        voiceChatController.release()
        super.onDestroy()
    }
   private fun initGlassesSdk() {
       val app = MyApplication.getInstance()
       app.createDirs(app.getAlbumDirFile().absolutePath)

       GlassesControl.getInstance(app)?.initGlasses(app.getAlbumDirFile().absolutePath)

       // WifiFilesDownloadListener: giữ voice callbacks gốc + thêm media sync
       GlassesControl.getInstance(app)?.setWifiDownloadListener(
           object : GlassesControl.WifiFilesDownloadListener {

               // ── Voice pipeline (từ code gốc) ──────────────────
               override fun voiceFromGlasses(pcmData: ByteArray) {
                   voiceChatController.onGlassesPcm(pcmData)
               }
               override fun voiceFromGlassesStatus(status: Int) {
                   voiceChatController.onGlassesVoiceStatus(status)
               }

               // ── Media sync callbacks ──────────────────────────
               override fun fileCount(index: Int, total: Int) {
                   runOnUiThread {
                       binding.tvSyncProgressLabel.text = "Đang tải file $index / $total"
                   }
               }
               override fun fileProgress(fileName: String, progress: Int) {
                   runOnUiThread {
                       binding.layoutSyncProgress.visibility = View.VISIBLE
                       binding.progressSync.progress = progress
                       binding.tvSyncProgressLabel.text = "$fileName  $progress%"
                   }
               }
               override fun fileWasDownloadSuccessfully(entity: GlassAlbumEntity) {
                   runOnUiThread { appendLog("✅ Đã tải: ${entity.fileName}") }
               }
               override fun fileDownloadComplete() {
                   runOnUiThread {
                       binding.layoutSyncProgress.visibility = View.GONE
                       appendLog("🎉 Đồng bộ hoàn tất!")
                       checkUnsyncedMedia()
                   }
               }
               override fun fileDownloadError(fileType: Int, errorType: Int) {
                   runOnUiThread { appendLog("❌ Lỗi tải file (loại=$fileType mã=$errorType)") }
               }
               override fun wifiSpeed(wifiSpeed: String) {
                   runOnUiThread { appendLog("Wi-Fi: $wifiSpeed") }
               }

               // ── Recording conversion callbacks ────────────────
               override fun recordingToPcm(fileName: String, filePath: String, duration: Int) {
                   runOnUiThread { appendLog("🎙 Ghi âm xong: $fileName  ${duration}ms") }
               }
               override fun recordingToPcmError(fileName: String, errorInfo: String) {
                   runOnUiThread { appendLog("❌ Lỗi ghi âm: $fileName  $errorInfo") }
               }

               override fun onGlassesControlSuccess() =
                   runOnUiThread { appendLog("Glasses control OK") }
               override fun onGlassesFail(errorCode: Int) =
                   runOnUiThread { appendLog("Glasses control fail: $errorCode") }
               override fun eisEnd(fileName: String, filePath: String) = Unit
               override fun eisError(fileName: String, sourcePath: String, errorInfo: String) = Unit
           }
       )

       // BLE notify listener
       LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
   }
    private fun bindViews() {
        binding.websocketUrl.setText(VoiceAgentConfig.DEFAULT_WEBSOCKET_URL)

        binding.btnScan.setOnClickListener {
            if (!ensureLocationEnabled()) return@setOnClickListener
            requestLocationPermission(this, object : PermissionCallback() {
                override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                    if (all) startKtxActivity<DeviceBindActivity>()
                }
            })
        }

        binding.btnConnect.setOnClickListener {
            if (!ensureLocationEnabled()) return@setOnClickListener
            BleOperateManager.getInstance().connectDirectly(DeviceManager.getInstance().deviceAddress)
            updateConnectionStatus(connecting = true)
            appendLog("Đang kết nối lại kính…")
        }

        binding.btnDisconnect.setOnClickListener {
            BleOperateManager.getInstance().unBindDevice()
            appendLog("Glasses disconnected")
            updateConnectionStatus(connected = false)
            appendLog("Đã ngắt kết nối kính")
        }

        binding.btnStartVoice.setOnClickListener {
            if (!ensureLocationEnabled()) return@setOnClickListener
            val config = VoiceAgentConfig()
            binding.websocketUrl.setText(config.websocketUrl)
            Log.d("socketurl", config.websocketUrl)
            voiceChatController.start(config)
        }

        binding.btnStopVoice.setOnClickListener {
            setGlassesVoiceCapture(start = false)
            voiceChatController.stop()
        }
        binding.btnApplyVoice.setOnClickListener { applySelectedVoice() }
//        binding.btnVoiceSettings.setOnClickListener {
//            startActivity(Intent(this, VoiceSettingsActivity::class.java))
//        }

        // ── Media Controls ─────────────────────────────────────────

        // 📷 Chụp ảnh — người dùng nhấn nút hoặc nhấn camera button trên kính
        binding.btnTakePhoto.setOnClickListener {
            appendLog("📷 Đang ra lệnh chụp ảnh…")
            mediaManager.takePhoto(object : MediaManager.MediaResultCallback {
                override fun onSuccess(message: String) = runOnUiThread { appendLog(message) }
                override fun onError(errorCode: Int, message: String) =
                    runOnUiThread { appendLog("❌ $message (code=$errorCode)") }
            })
        }

        // 🎥 Bật/tắt quay video
        binding.btnToggleVideo.setOnClickListener {
            val starting = !mediaManager.isRecordingVideo
            appendLog(if (starting) "🎥 Đang ra lệnh quay video…" else "⏹ Đang dừng quay video…")
            mediaManager.toggleVideoRecording(object : MediaManager.MediaResultCallback {
                override fun onSuccess(message: String) = runOnUiThread {
                    appendLog(message)
                    updateVideoButton()
                }
                override fun onError(errorCode: Int, message: String) =
                    runOnUiThread { appendLog("❌ $message (code=$errorCode)") }
            })
        }

        // 🎙 Bật/tắt ghi âm
        binding.btnToggleAudio.setOnClickListener {
            val starting = !mediaManager.isRecordingAudio
            appendLog(if (starting) "🎙 Đang ra lệnh ghi âm…" else "⏹ Đang dừng ghi âm…")
            mediaManager.toggleAudioRecording(object : MediaManager.MediaResultCallback {
                override fun onSuccess(message: String) = runOnUiThread {
                    appendLog(message)
                    updateAudioButton()
                }
                override fun onError(errorCode: Int, message: String) =
                    runOnUiThread { appendLog("❌ $message (code=$errorCode)") }
            })
        }

        // 🔍 Kiểm tra số file chưa đồng bộ
        binding.btnCheckMedia.setOnClickListener { checkUnsyncedMedia() }

        // 🔄 Đồng bộ media từ kính về điện thoại
        binding.btnSyncMedia.setOnClickListener {
            appendLog("🔄 Bắt đầu đồng bộ media từ kính…")
            binding.layoutSyncProgress.visibility = View.VISIBLE
            binding.progressSync.progress = 0
            syncManager.startSyncMedia()
        }
        binding.btnOpenGallery.setOnClickListener {
            startActivity(Intent(this, MediaGalleryActivity::class.java))
        }

    }
    private fun loadVoiceSelector() {
        binding.progressVoiceSelector.visibility = View.VISIBLE
        binding.spinnerVoice.isEnabled = false
        Log.d("VoiceAgent:","Check agent")
        lifecycleScope.launch {
            try {
                // Gọi song song: agent detail + collections
                val agentDeferred =  agentRepo.getAgent()
                Log.d("VoiceAgent","${agentDeferred.tts_collection_id}")
                val collsDeferred = agentRepo.getCollections()

                val agent  = agentDeferred
                val colls  = collsDeferred

                currentCollectionId  = agent.tts_collection_id
                selectedCollectionId = currentCollectionId
                voiceCollections     = colls

                // Bind spinner
                val names = colls.map { it.name }
                val adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    names
                ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

                binding.spinnerVoice.adapter = adapter

                // Chọn sẵn giọng đang dùng
                val selectedIndex = colls.indexOfFirst { it.id == currentCollectionId }
                if (selectedIndex >= 0) binding.spinnerVoice.setSelection(selectedIndex)

                binding.spinnerVoice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                        selectedCollectionId = voiceCollections.getOrNull(pos)?.id
                        // Chỉ enable nút Áp dụng khi chọn giọng khác với giọng hiện tại
                        binding.btnApplyVoice.isEnabled = selectedCollectionId != currentCollectionId
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                binding.spinnerVoice.isEnabled = true
                binding.progressVoiceSelector.visibility = View.GONE

            } catch (e: Exception) {
                binding.progressVoiceSelector.visibility = View.GONE
                appendLog("⚠️ Không tải được danh sách giọng: ${e.message}")
            }
        }
    }
    /**
     * Áp dụng giọng đã chọn qua PATCH /agents.
     * Gọi từ binding.btnApplyVoice.setOnClickListener.
     */
    private fun applySelectedVoice() {
        val colId = selectedCollectionId ?: return
        binding.btnApplyVoice.isEnabled = false
        binding.btnApplyVoice.text = "Đang lưu…"

        lifecycleScope.launch {
            try {
                agentRepo.updateVoice(colId)
                currentCollectionId = colId
                val name = voiceCollections.find { it.id == colId }?.name ?: colId
                appendLog("✅ Giọng nói đã đổi sang: $name")
                binding.btnApplyVoice.text = "Áp dụng"
                // Disable vì không còn thay đổi nào pending
                binding.btnApplyVoice.isEnabled = false
            } catch (e: Exception) {
                appendLog("❌ Đổi giọng thất bại: ${e.message}")
                binding.btnApplyVoice.text = "Áp dụng"
                binding.btnApplyVoice.isEnabled = true
            }
        }
    }
    // ─────────────────────────────────────────────────────────────
    // CHỤP ẢNH — có debounce chống spam
    // FIX: photoInFlight flag ngăn gửi lệnh chụp ảnh liên tiếp
    // trước khi callback về. Nguyên nhân spam: nút kính gửi nhiều
    // event nhanh (press + release + repeat) → gọi doTakePhoto()
    // nhiều lần trong vài ms.
    // ─────────────────────────────────────────────────────────────
    private fun doTakePhoto() {
        if (photoInFlight) {
            Log.d(TAG, "doTakePhoto: skipped (in-flight)")
            return
        }
        photoInFlight = true
        appendLog("📷 Đang chụp ảnh…")

        mediaManager.takePhoto(object : MediaManager.MediaResultCallback {
            override fun onSuccess(message: String) = runOnUiThread {
                photoInFlight = false
                appendLog(message)
                // Sau 2s tự động kéo ảnh mới về nếu có
                mainHandler.postDelayed({ checkUnsyncedMedia() }, 2_000L)
            }
            override fun onError(errorCode: Int, message: String) = runOnUiThread {
                photoInFlight = false
                appendLog("❌ $message (code=$errorCode)")
            }
        })

        // Safety timeout: reset flag sau 5s phòng callback không về
        mainHandler.postDelayed({
            if (photoInFlight) {
                photoInFlight = false
                Log.w(TAG, "doTakePhoto: timeout reset")
            }
        }, 5_000L)
    }
    private fun refreshDeviceCard() {
        val address = DeviceManager.getInstance()?.deviceAddress
        val isConnected = BleOperateManager.getInstance()?.isConnected == true

        val deviceName = if (!address.isNullOrEmpty()) {
            // Lấy tên thiết bị đã lưu qua BLE
            val name = try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) null
                else adapter?.getRemoteDevice(address)?.name
            } catch (_: Exception) { null }
            name ?: address
        } else "Chưa kết nối"

        binding.tvDeviceName.text = deviceName
        updateConnectionStatus(connected = isConnected)
    }

    /**
     * Cập nhật trạng thái kết nối + màu dot trên card
     * connecting = true → hiện "Đang kết nối…" (chờ)
     */
    fun updateConnectionStatus(connected: Boolean = false, connecting: Boolean = false) {
        runOnUiThread {
            when {
                connecting -> {
                    binding.tvConnectionStatus.text = "Đang kết nối…"
                    binding.tvConnectionStatus.setTextColor(0xFFFFA726.toInt()) // amber
                    binding.viewStatusDot.setBackgroundResource(0) // clear
                    binding.viewStatusDot.setBackgroundColor(0xFFFFA726.toInt())
                }
                connected -> {
                    binding.tvConnectionStatus.text = "Đã kết nối"
                    binding.tvConnectionStatus.setTextColor(0xFF00C853.toInt()) // green
                    binding.viewStatusDot.setBackgroundResource(R.drawable.ic_status_dot_connected)
                }
                else -> {
                    binding.tvConnectionStatus.text = "Không kết nối"
                    binding.tvConnectionStatus.setTextColor(0xFF8BAED4.toInt()) // muted blue
                    binding.viewStatusDot.setBackgroundResource(R.drawable.ic_status_dot_disconnected)
                    updateBattery(0)
                }
            }
        }
    }
    /**
     * Cập nhật thanh pin — gọi từ DeviceNotifyListener khi nhận 0x05
     * @param pct 0–100
     */
    private fun updateBattery(pct: Int) {
        runOnUiThread {
            binding.tvBatteryPct.text = "$pct%"
            // Chiều rộng thanh fill = tỉ lệ của parent (80dp)
            val parent = binding.viewBatteryFill.parent as FrameLayout
            val totalW = parent.width
            if (totalW > 0) {
                val lp = binding.viewBatteryFill.layoutParams
                lp.width = (totalW * pct / 100).coerceIn(0, totalW)
                binding.viewBatteryFill.layoutParams = lp
            }
            // Màu theo mức pin
            val color = when {
                pct > 50 -> 0xFF00C853.toInt()  // xanh lá
                pct > 20 -> 0xFFFFA726.toInt()  // cam
                else     -> 0xFFE53935.toInt()  // đỏ
            }
            binding.viewBatteryFill.setBackgroundColor(color)
            // Emoji icon
            binding.tvBatteryIcon.text = when {
                pct > 80 -> "🔋"
                pct > 50 -> "🔋"
                pct > 20 -> "🪫"
                else     -> "🪫"
            }
            appendLog("🔋 Pin kính: $pct%")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // NÚT KÍNH — xử lý button events từ GlassesDeviceNotifyRsp
    //
    // Protocol loadData (từ doc + reverse engineer SDK):
    //   loadData[6] = 0x03  → Nút TRÁI (Voice)
    //     loadData[7] = 1   → nhấn xuống
    //     loadData[7] = 0   → nhả ra
    //     → đếm số lần nhấn-nhả để xác định single/double click
    //
    //   loadData[6] = 0x01  → Nút PHẢI (Camera)
    //     loadData[7] = 1   → nhấn xuống
    //     loadData[7] = 0   → nhả ra
    //     → đếm single/double click tương tự
    //
    //   loadData[6] = 0x05  → Báo pin
    //   loadData[6] = 0x0C  → Pause/broadcast event
    // ─────────────────────────────────────────────────────────────
    inner class DeviceNotifyListener : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val d = response.loadData
            if (d.size <= 7) return

            when (d[6].toInt() and 0xFF) {

                // ── NÚT PHẢI (Camera button) ──────────────────────
                // loadData[7] = 1: nhấn xuống → đếm click
                0x01 -> {
                    val pressed = (d[7].toInt() and 0xFF) == 1
                    if (!pressed) return   // bỏ qua event nhả
                    runOnUiThread { handleRightButtonClick() }
                }

                // ── NÚT TRÁI (Voice button) ───────────────────────
                // loadData[7] = 1: nhấn xuống → đếm click
                0x03 -> {
                    val pressed = (d[7].toInt() and 0xFF) == 1
                    if (!pressed) return   // bỏ qua event nhả
                    runOnUiThread { handleLeftButtonClick() }
                }

                // ── Pin ───────────────────────────────────────────
                0x05 -> runOnUiThread {
                    updateBattery(d[7].toInt() and 0xFF)
                    appendLog("🔋 Pin kính: ${d[7].toInt() and 0xFF}%")
                }
                0x06 -> runOnUiThread {
                    refreshDeviceCard()
                    updateConnectionStatus(connected = true)
                    appendLog("✅ Kính đã kết nối")
                }
                // BLE disconnected event
                0x07 -> runOnUiThread {
                    updateConnectionStatus(connected = false)
                    binding.tvBatteryPct.text = "–"
                    appendLog("⚠️ Kính đã ngắt kết nối")
                }

                // ── Pause/broadcast ───────────────────────────────
                0x0C -> runOnUiThread {
                    appendLog("⏸ Kính: pause/broadcast event")
                }
            }
        }
    }
    // ─────────────────────────────────────────────────────────────
    // NÚT PHẢI logic:
    //   1 click  → Chụp ảnh
    //   2 clicks → Toggle quay video
    // ─────────────────────────────────────────────────────────────
    private fun handleRightButtonClick() {
        rightClickCount++
        Log.d(TAG, "Right button click #$rightClickCount")

        rightPendingRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val clicks = rightClickCount
            rightClickCount = 0
            when (clicks) {
                1 -> {
                    // Single click → chụp ảnh
                    appendLog("📷 [Kính] Nút phải 1× → Chụp ảnh")
                    doTakePhoto()
                }
                2 -> {
                    // Double click → toggle video
                    val starting = !mediaManager.isRecordingVideo
                    appendLog("🎥 [Kính] Nút phải 2× → ${if (starting) "Bắt đầu" else "Dừng"} quay video")
                    mediaManager.toggleVideoRecording(mediaCallback { updateVideoButton() })
                }
                else -> {
                    appendLog("📷 [Kính] Nút phải ${clicks}× → bỏ qua")
                }
            }
        }
        rightPendingRunnable = runnable
        mainHandler.postDelayed(runnable, DOUBLE_CLICK_MS)
    }

    // ─────────────────────────────────────────────────────────────
    // NÚT TRÁI logic:
    //   1 click  → Voice Chat (bắt đầu nếu chưa chạy, dừng nếu đang chạy)
    //   2 clicks → Toggle ghi âm độc lập
    // ─────────────────────────────────────────────────────────────
    private fun handleLeftButtonClick() {
        leftClickCount++
        Log.d(TAG, "Left button click #$leftClickCount")

        leftPendingRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            val clicks = leftClickCount
            leftClickCount = 0
            when (clicks) {
                1 -> {
                    // Single click → Voice Chat toggle
                    if (microphoneCapturing) {
                        appendLog("🎙 [Kính] Nút trái 1× → Dừng Voice Chat")
                        setGlassesVoiceCapture(false)
                        voiceChatController.stop()
                    }
                   /* else {
                        appendLog("🎙 [Kính] Nút trái 1× → Bắt đầu Voice Chat")
                        val config = VoiceAgentConfig()
                        binding.websocketUrl.setText(config.websocketUrl)
                        voiceChatController.start(config)
                        // setGlassesVoiceCapture(true) được gọi tự động khi VoiceChatEvent.Ready
                    }*/
                }
                2 -> {
                    // Double click → toggle ghi âm độc lập
                    val starting = !mediaManager.isRecordingAudio
                    appendLog("🎙 [Kính] Nút trái 2× → ${if (starting) "Bắt đầu" else "Dừng"} ghi âm")
                    mediaManager.toggleAudioRecording(mediaCallback { updateAudioButton() })
                }
                else -> {
                    appendLog("🎙 [Kính] Nút trái ${clicks}× → bỏ qua")
                }
            }
        }
        leftPendingRunnable = runnable
        mainHandler.postDelayed(runnable, DOUBLE_CLICK_MS)
    }

    // ─────────────────────────────────────────────────────────────
    // MediaResultCallback shorthand
    // ─────────────────────────────────────────────────────────────
    private fun mediaCallback(onSuccess: () -> Unit) =
        object : MediaManager.MediaResultCallback {
            override fun onSuccess(message: String) = runOnUiThread {
                appendLog(message); onSuccess()
            }
            override fun onError(errorCode: Int, message: String) =
                runOnUiThread { appendLog("❌ $message (code=$errorCode)") }
        }

    // ─────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────
    private fun checkUnsyncedMedia() {
        mediaManager.checkUnsyncedMedia { total, image, video, audio ->
            runOnUiThread {
                binding.tvUnsyncedCount.text = if (total == 0)
                    getString(R.string.unsynced_none)
                else
                    "📦 $total file  (ảnh=$image  video=$video  âm=$audio)"
                if (total > 0) appendLog("Kính có $total file chưa đồng bộ")
            }
        }
    }

    private fun updateVideoButton() {
        val rec = mediaManager.isRecordingVideo
        binding.btnToggleVideo.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (rec) 0xFFB71C1C.toInt() else 0xFFD32F2F.toInt()
            )
        binding.tvVideoLabel.text =
            getString(if (rec) R.string.action_stop_video else R.string.action_start_video)
    }

    private fun updateAudioButton() {
        val rec = mediaManager.isRecordingAudio
        binding.btnToggleAudio.backgroundTintList =
            android.content.res.ColorStateList.valueOf(
                if (rec) 0xFF1B5E20.toInt() else 0xFF388E3C.toInt()
            )
        binding.tvAudioLabel.text =
            getString(if (rec) R.string.action_stop_audio else R.string.action_start_audio)
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Permission.RECORD_AUDIO)
            add(Permission.ACCESS_COARSE_LOCATION)
            add(Permission.ACCESS_FINE_LOCATION)
            add(Permission.BLUETOOTH_SCAN)
            add(Permission.BLUETOOTH_CONNECT)
            add(Permission.BLUETOOTH_ADVERTISE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Permission.READ_MEDIA_AUDIO)
                add(Permission.READ_MEDIA_IMAGES)
                add(Permission.READ_MEDIA_VIDEO)
                add(Permission.NEARBY_WIFI_DEVICES)
            }
        }

        XXPermissions.with(this)
            .permission(permissions)
            .request(object : PermissionCallback() {})
    }
private fun ensureBluetoothEnabled() {
    ensureLocationEnabled()
    try {
        if (!BluetoothUtils.isEnabledBluetooth(this)) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) return
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 300)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot request Bluetooth enable", e)
    }
    if (!hasBluetooth(this)) requestBluetoothPermission(this, object : PermissionCallback() {})
}

    private fun ensureLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
        if (!enabled) {
            appendLog("⚠️ Cần bật Location để kết nối kính BLE")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        return enabled
    }

    private fun setGlassesVoiceCapture(start: Boolean) {
        if (microphoneCapturing == start) return
        val command = if (start) 0x08 else 0x0c
        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, command.toByte())) { _, rsp ->
            if (rsp.errorCode == 0) {
                microphoneCapturing = start
            }
            appendLog(
                "Glasses voice capture ${if (start) "start" else "stop"} " +
                    "result=${rsp.errorCode} dataType=${rsp.dataType} workType=${rsp.workTypeIng}"
            )
        }
    }
   // ─────────────────────────────────────────────────────────────
   // Voice capture — bật/tắt mic streaming từ kính lên app
   // ─────────────────────────────────────────────────────────────
   /*private fun setGlassesVoiceCapture(start: Boolean) {
       if (microphoneCapturing == start) return
       val cmd = if (start) 0x08.toByte() else 0x0C.toByte()
       LargeDataHandler.getInstance().glassesControl(
           byteArrayOf(0x02, 0x01, cmd),
           controlCallback { _, rsp ->
               if (rsp.errorCode == 0) microphoneCapturing = start
               appendLog(
                   "VoiceCapture ${if (start) "ON" else "OFF"} → " +
                           "err=${rsp.errorCode} data=${rsp.dataType} work=${rsp.workTypeIng}"
               )
           }
       )
   }*/

    private fun renderVoiceEvent(event: VoiceChatEvent) {
        when (event) {
            VoiceChatEvent.Ready -> {
                appendLog("Voice agent ready")
                setGlassesVoiceCapture(start = true)
            }
            VoiceChatEvent.Disconnected -> setGlassesVoiceCapture(start = false)
            is VoiceChatEvent.Status -> appendLog(event.message)
            is VoiceChatEvent.AgentText -> appendLog("Agent: ${event.message}")
            is VoiceChatEvent.Error -> {
                appendLog("Error: ${event.message}")
                setGlassesVoiceCapture(start = false)
            }
        }
    }

    private fun appendLog(message: String) {
        binding.statusLog.append("${System.currentTimeMillis()}  $message\n")
    }

    open inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (!all) appendLog("Some permissions were not granted")
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            if (never) XXPermissions.startPermissionActivity(this@MainActivity, permissions)
        }
    }



    companion object {
        private const val TAG = "HeyCyanVoiceAgent"
    }
}
