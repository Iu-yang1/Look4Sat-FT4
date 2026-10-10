package org.iu.radio.usbrecorder

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10

/**
 * Alpha diagnostic recorder:
 * - USB permission is requested explicitly.
 * - No microphone permission or AudioRecord fallback.
 * - Recordings are written using SAF to a 16-bit PCM WAV.
 * - Keep this Activity open for alpha testing (no background service yet).
 */
class MainActivity : Activity() {
    private val permissionAction = "org.iu.radio.usbrecorder.USB_PERMISSION"
    private val requestWav = 9001
    private lateinit var usbManager: UsbManager
    private lateinit var info: TextView
    private lateinit var metrics: TextView
    private lateinit var deviceButton: Button
    private lateinit var recordButton: Button
    private lateinit var stopButton: Button
    private var device: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var recording = false
    private var startedAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != permissionAction) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                pickWavFile()
            } else {
                showMessage("USB access denied. No fallback to phone microphone.")
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (recording) {
                try {
                    val values = NativeRecorder.snapshot()
                    if (values.size >= 4) {
                        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
                        val peak = values[2] / 1_000_000.0
                        val level = if (peak > 0.0) "%.1f dBFS".format(Locale.US, 20 * log10(peak)) else "-inf dBFS"
                        metrics.text = "Duration: " + seconds + "s\nPCM bytes: " + values[0] +
                            "\nDropped packets: " + values[1] + "\nRecent peak: " + level +
                            "\nUAC transfer error: " + values[3]
                        if (values[3] != 0L) {
                            stopRecording()
                            showMessage("USB stream failure (" + values[3] + "). Stopped; no mic fallback.")
                        }
                    }
                } catch (e: Exception) {
                    showMessage("Status error: " + e.message)
                }
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerUsbPermissionReceiver()
        buildUi()
        refreshDevice()
        handler.post(ticker)
    }

    private fun registerUsbPermissionReceiver() {
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun label(text: String, size: Float): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setPadding(0, dp(9), 0, dp(9))
            }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(22))
        }
        val title = label("Radio USB Recorder", 24f)
        title.gravity = Gravity.CENTER_HORIZONTAL
        layout.addView(title)
        layout.addView(label("USB/UAC direct PCM • No AGC / NS / AEC in app", 13f))
        info = label("Detecting USB audio input…", 15f)
        layout.addView(info)
        deviceButton = Button(this).apply {
            text = "Refresh USB devices"
            setOnClickListener { refreshDevice() }
        }
        layout.addView(deviceButton)
        recordButton = Button(this).apply {
            text = "Start raw WAV recording"
            setOnClickListener { requestRecording() }
        }
        layout.addView(recordButton)
        stopButton = Button(this).apply {
            text = "Stop and finalize WAV"
            isEnabled = false
            setOnClickListener { stopRecording() }
        }
        layout.addView(stopButton)
        metrics = label("No recording active.", 15f)
        layout.addView(metrics)
        layout.addView(label(
            "ALPHA: keep this screen open. 48 kHz / 16-bit / mono requested; hardware support checked at start. " +
                "USB capture only; no AudioRecord, no internal microphone fallback. " +
                "WAV saved through Android file picker.",
            13f
        ))
        val scroll = ScrollView(this)
        scroll.addView(layout, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(scroll)
    }

    private fun isAudioInput(candidate: UsbDevice): Boolean =
        (0 until candidate.interfaceCount).any { index ->
            val intf = candidate.getInterface(index)
            intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                intf.interfaceSubclass == 2
        }

    private fun refreshDevice() {
        if (recording) return
        val devices = usbManager.deviceList.values.filter(::isAudioInput)
        device = devices.firstOrNull()
        val selected = device
        info.text = if (selected == null) {
            "No USB AudioStreaming interface. Connect AB13X in USB host mode."
        } else {
            "Device: " + selected.deviceName +
                "\nVID:PID = %04X:%04X".format(
                    Locale.US, selected.vendorId, selected.productId
                ) +
                "\nUSB permission: " + usbManager.hasPermission(selected) +
                "\nCandidates: " + devices.size
        }
        recordButton.isEnabled = selected != null
    }

    private fun requestRecording() {
        val selected = device ?: run {
            showMessage("No USB input selected.")
            return
        }
        if (recording) return
        if (usbManager.hasPermission(selected)) {
            pickWavFile()
            return
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(permissionAction).setPackage(packageName), flags
        )
        usbManager.requestPermission(selected, permissionIntent)
    }

    private fun pickWavFile() {
        val name = "radio-usb-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, requestWav)
    }

    @Deprecated("Legacy SAF activity result for dependency-free prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestWav || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val selected = device ?: return
        if (!usbManager.hasPermission(selected)) {
            showMessage("USB permission no longer present.")
            return
        }
        var outputFd: Int? = null
        try {
            val connection = usbManager.openDevice(selected)
                ?: throw IllegalStateException("USB openDevice() failed")
            usbConnection = connection
            val descriptor = contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("Cannot open WAV destination")
            outputFd = descriptor.detachFd()
            descriptor.close()
            // JNI always closes/destroys outputFd, even when start fails.
            val result = NativeRecorder.start(connection.fileDescriptor, outputFd, 48_000)
            outputFd = null
            if (result != null) throw IllegalStateException(result)
            recording = true
            startedAt = SystemClock.elapsedRealtime()
            recordButton.isEnabled = false
            deviceButton.isEnabled = false
            stopButton.isEnabled = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            showMessage("Recording directly from USB to " + uri)
        } catch (e: Exception) {
            outputFd?.let { android.os.ParcelFileDescriptor.adoptFd(it).close() }
            // The outputFd path is only reached before NativeRecorder.start().
            usbConnection?.close()
            usbConnection = null
            showMessage("Start failed: " + e.message)
            refreshDevice()
        }
    }

    private fun stopRecording() {
        if (!recording) return
        try {
            val error = NativeRecorder.stop()
            showMessage(if (error == null) "WAV finalized." else "Stopped: " + error)
        } catch (e: Exception) {
            showMessage("Stop failed: " + e.message)
        } finally {
            recording = false
            usbConnection?.close()
            usbConnection = null
            recordButton.isEnabled = device != null
            deviceButton.isEnabled = true
            stopButton.isEnabled = false
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun showMessage(message: String) {
        info.text = message + "\n\n" + (device?.let {
            "VID:PID %04X:%04X".format(Locale.US, it.vendorId, it.productId)
        } ?: "No device")
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        stopRecording()
        usbConnection?.close()
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }
}
