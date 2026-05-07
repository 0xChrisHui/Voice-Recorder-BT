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
     * Returns the first connected Bluetooth INPUT device — used by the UI to display the headset
     * name and by callers that just want to detect "is a BT headset connected for recording?".
     *
     * NOT suitable for [AudioManager.setCommunicationDevice], which expects an *output* device.
     * Use [pickBluetoothCommunicationDevice] for routing.
     */
    fun currentBluetoothInputDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.firstOrNull { d -> isBluetoothInputType(d) }
    }

    /**
     * Returns the Bluetooth output device that the platform exposes for *communication*. This is
     * the only [AudioDeviceInfo] that [AudioManager.setCommunicationDevice] will accept on API 31+
     * — passing an input device is silently rejected, leaving the SCO link unestablished and
     * AudioRecord reads stuck at zero.
     *
     * Returns null on API < 31 (legacy path uses [AudioManager.startBluetoothSco] instead, which
     * needs no device argument).
     */
    fun pickBluetoothCommunicationDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return audioManager.availableCommunicationDevices.firstOrNull { d ->
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }

    /**
     * Ask the audio framework to route communication audio (input AND output) to a connected
     * Bluetooth headset, if one is available. Returns true if the request was accepted; the
     * actual SCO handshake is asynchronous — call [waitForBluetoothRoute] to block until ready.
     */
    @SuppressLint("MissingPermission")
    fun requestBluetoothScoRoute(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDev = pickBluetoothCommunicationDevice()
                if (commDev == null) {
                    Log.w(TAG, "No Bluetooth communication device available; cannot route")
                    return false
                }
                val ok = audioManager.setCommunicationDevice(commDev)
                Log.d(TAG, "setCommunicationDevice(${commDev.productName}) -> $ok")
                ok
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

    private fun isBluetoothInputType(d: AudioDeviceInfo): Boolean {
        if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) return true
        return false
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
     * Block (busy-wait) until the audio framework reports a Bluetooth communication device is
     * active, or [timeoutMs] elapses. [requestBluetoothScoRoute] returns immediately once the
     * request is accepted but the SCO link itself takes ~500ms-2s to come up — opening an
     * AudioRecord before this resolves yields an instance bound to the wrong input, with reads
     * stuck at zero forever (the symptom: 1-second empty recordings).
     *
     * Returns true if a BT route is now active.
     */
    fun waitForBluetoothRoute(timeoutMs: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            while (System.currentTimeMillis() < deadline) {
                val current = audioManager.communicationDevice
                if (current != null && isBluetoothInputType(current)) {
                    Log.d(TAG, "BT communication active: ${current.productName}")
                    return true
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
            Log.w(
                TAG,
                "Timed out waiting for BT route. Current communicationDevice=" +
                    "${audioManager.communicationDevice?.productName} " +
                    "(type=${audioManager.communicationDevice?.type})"
            )
            return false
        }
        while (System.currentTimeMillis() < deadline) {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothScoOn) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
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
            // The recorder thread is responsible for actually requesting the SCO route + waiting
            // for it to come up; we only notify here. That keeps the blocking poll off the main
            // thread (this callback is on the main handler).
            listener?.onBluetoothConnected(btDevice!!)
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
