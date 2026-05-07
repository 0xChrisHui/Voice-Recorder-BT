package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Owns Bluetooth-headset audio routing for recording.
 *
 * Responsibilities:
 *  - Watch for BT input devices being connected/disconnected.
 *  - When connected, request the audio framework to route the mic input to BT (SCO/HFP or LE Audio).
 *  - Notify a single recorder listener so it can swap [android.media.AudioRecord] input device.
 *
 * Notes:
 *  - On API 31+ uses [AudioManager.setCommunicationDevice], which is the modern, non-deprecated path.
 *  - On API 26..30 falls back to [AudioManager.startBluetoothSco] (deprecated but works).
 *  - Connect/disconnect events from the OS can fire several times in quick succession; debounce 500ms.
 */
class BluetoothAudioController(private val context: Context) {

    interface OnRouteChangeListener {
        fun onBluetoothConnected(device: AudioDeviceInfo)
        fun onBluetoothDisconnected()
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: OnRouteChangeListener? = null
    private var lastNotifiedConnected: Boolean = false
    private var deviceCallback: AudioDeviceCallback? = null
    private var pendingDebounce: Runnable? = null
    private var isStarted = false

    fun setListener(l: OnRouteChangeListener?) {
        listener = l
    }

    fun start() {
        if (isStarted) return
        isStarted = true

        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                scheduleEvaluate()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                scheduleEvaluate()
            }
        }
        deviceCallback = cb
        audioManager.registerAudioDeviceCallback(cb, mainHandler)
        // Initial evaluation in case BT is already connected when we start.
        scheduleEvaluate()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        pendingDebounce?.let { mainHandler.removeCallbacks(it) }
        pendingDebounce = null
        releaseBluetoothScoRoute()
        listener = null
    }

    /**
     * Returns the first connected Bluetooth INPUT device, or null if none.
     */
    fun currentBluetoothInputDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.firstOrNull { d ->
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    /**
     * Ask the audio framework to route the mic input to the given BT device.
     * Returns true if the request was accepted (not necessarily yet in effect).
     */
    @SuppressLint("MissingPermission")
    fun requestBluetoothScoRoute(device: AudioDeviceInfo): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Modern path. Returns true if the request was accepted.
                audioManager.setCommunicationDevice(device)
            } else {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestBluetoothScoRoute failed", e)
            false
        }
    }

    fun releaseBluetoothScoRoute() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        } catch (e: Exception) {
            Log.w(TAG, "releaseBluetoothScoRoute failed", e)
        }
    }

    /**
     * Block (busy-wait) until the audio framework's communication device matches [target] or
     * we run out of time. setCommunicationDevice() returns immediately with `true` once it
     * accepts the request, but the SCO link itself takes a moment to establish — if you create
     * an AudioRecord before this resolves, you get an instance bound to the previous (wrong)
     * input and reads return 0 forever.
     *
     * Returns true if the route is now active.
     */
    fun waitForCommunicationDeviceMatch(target: AudioDeviceInfo, timeoutMs: Long = 3000): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // On older API, poll isBluetoothScoOn instead.
            val deadlineLegacy = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadlineLegacy) {
                @Suppress("DEPRECATION")
                if (audioManager.isBluetoothScoOn) return true
                Thread.sleep(POLL_INTERVAL_MS)
            }
            return false
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = audioManager.communicationDevice
            if (current?.id == target.id) {
                Log.d(TAG, "Communication device active: ${current.productName}")
                return true
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        Log.w(
            TAG,
            "Timed out waiting for communication device. Wanted ${target.productName}, got " +
                "${audioManager.communicationDevice?.productName}"
        )
        return false
    }

    private fun scheduleEvaluate() {
        pendingDebounce?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { evaluate() }
        pendingDebounce = r
        mainHandler.postDelayed(r, DEBOUNCE_MS)
    }

    private fun evaluate() {
        val btDevice = currentBluetoothInputDevice()
        val nowConnected = btDevice != null
        if (nowConnected == lastNotifiedConnected) return

        lastNotifiedConnected = nowConnected
        if (nowConnected) {
            requestBluetoothScoRoute(btDevice!!)
            listener?.onBluetoothConnected(btDevice)
        } else {
            releaseBluetoothScoRoute()
            listener?.onBluetoothDisconnected()
        }
    }

    companion object {
        private const val TAG = "BtAudioCtrl"
        private const val DEBOUNCE_MS = 500L
        private const val POLL_INTERVAL_MS = 100L
    }
}
