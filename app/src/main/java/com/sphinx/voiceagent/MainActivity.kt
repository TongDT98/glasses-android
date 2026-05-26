package com.sphinx.voiceagent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.wifi.GlassesControl
import com.oudmon.wifi.bean.GlassAlbumEntity
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
import com.sphinx.voiceagent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceChatController: VoiceChatController
    private val deviceNotifyListener = DeviceNotifyListener()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        voiceChatController = VoiceChatController(::renderVoiceEvent)
        initGlassesSdk()
        bindViews()
        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        ensureBluetoothEnabled()
    }

    override fun onDestroy() {
        voiceChatController.stop()
        super.onDestroy()
    }

    private fun initGlassesSdk() {
        MyApplication.getInstance().createDirs(MyApplication.getInstance().getAlbumDirFile().absolutePath)
        GlassesControl.getInstance(MyApplication.getInstance())
            ?.initGlasses(MyApplication.getInstance().getAlbumDirFile().absolutePath)
        LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)

        GlassesControl.getInstance(MyApplication.getInstance())
            ?.setWifiDownloadListener(object : GlassesControl.WifiFilesDownloadListener {
                override fun voiceFromGlasses(pcmData: ByteArray) {
                    voiceChatController.onGlassesPcm(pcmData)
                }

                override fun voiceFromGlassesStatus(status: Int) {
                    voiceChatController.onGlassesVoiceStatus(status)
                }

                override fun recordingToPcm(fileName: String, filePath: String, duration: Int) {
                    appendLog("Recording converted: $fileName duration=${duration}ms")
                }

                override fun recordingToPcmError(fileName: String, errorInfo: String) {
                    appendLog("Recording convert error: $fileName $errorInfo")
                }

                override fun eisEnd(fileName: String, filePath: String) = Unit
                override fun eisError(fileName: String, sourcePath: String, errorInfo: String) = Unit
                override fun fileCount(index: Int, total: Int) = Unit
                override fun fileDownloadComplete() = Unit
                override fun fileDownloadError(fileType: Int, errorType: Int) = Unit
                override fun fileProgress(fileName: String, progress: Int) = Unit
                override fun fileWasDownloadSuccessfully(entity: GlassAlbumEntity) = Unit
                override fun onGlassesControlSuccess() = appendLog("Glasses control success")
                override fun onGlassesFail(errorCode: Int) = appendLog("Glasses control failed: $errorCode")
                override fun wifiSpeed(wifiSpeed: String) = Unit
            })
    }

    private fun bindViews() {
        binding.websocketUrl.setText(VoiceAgentConfig.DEFAULT_WEBSOCKET_URL)

        binding.btnScan.setOnClickListener {
            requestLocationPermission(this, object : PermissionCallback() {
                override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                    if (all) startKtxActivity<DeviceBindActivity>()
                }
            })
        }

        binding.btnConnect.setOnClickListener {
            BleOperateManager.getInstance().connectDirectly(DeviceManager.getInstance().deviceAddress)
            appendLog("Connecting to saved glasses address...")
        }

        binding.btnDisconnect.setOnClickListener {
            BleOperateManager.getInstance().unBindDevice()
            appendLog("Glasses disconnected")
        }

        binding.btnStartVoice.setOnClickListener {
            val config = VoiceAgentConfig()
            binding.websocketUrl.setText(config.websocketUrl)
            Log.d("socketurl", config.websocketUrl)
            voiceChatController.start(config)
            setGlassesVoiceCapture(start = true)
        }

        binding.btnStopVoice.setOnClickListener {
            setGlassesVoiceCapture(start = false)
            voiceChatController.stop()
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Permission.RECORD_AUDIO)
            add(Permission.ACCESS_FINE_LOCATION)
            add(Permission.BLUETOOTH_SCAN)
            add(Permission.BLUETOOTH_CONNECT)
            add(Permission.BLUETOOTH_ADVERTISE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Permission.READ_MEDIA_AUDIO)
                add(Permission.READ_MEDIA_IMAGES)
                add(Permission.READ_MEDIA_VIDEO)
            }
        }

        XXPermissions.with(this)
            .permission(permissions)
            .request(object : PermissionCallback() {})
    }

    private fun ensureBluetoothEnabled() {
        try {
            if (!BluetoothUtils.isEnabledBluetooth(this)) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                startActivityForResult(intent, 300)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot request Bluetooth enable", e)
        }

        if (!hasBluetooth(this)) {
            requestBluetoothPermission(this, object : PermissionCallback() {})
        }
    }

    private fun setGlassesVoiceCapture(start: Boolean) {
        val command = if (start) 0x08 else 0x0c
        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, command.toByte())) { _, rsp ->
            appendLog("Glasses voice capture ${if (start) "start" else "stop"} result=${rsp.errorCode}")
        }
    }

    private fun renderVoiceEvent(event: VoiceChatEvent) {
        when (event) {
            is VoiceChatEvent.Status -> appendLog(event.message)
            is VoiceChatEvent.AgentText -> appendLog("Agent: ${event.message}")
            is VoiceChatEvent.Error -> appendLog("Error: ${event.message}")
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

    inner class DeviceNotifyListener : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            if (response.loadData.size <= 7) return

            when (response.loadData[6].toInt()) {
                0x03 -> if (response.loadData[7].toInt() == 1) {
                    appendLog("Glasses microphone activated")
                }
                0x05 -> appendLog("Battery: ${response.loadData[7].toInt()}%")
                0x0c -> appendLog("Glasses pause/broadcast event")
            }
        }
    }

    companion object {
        private const val TAG = "HeyCyanVoiceAgent"
    }
}
