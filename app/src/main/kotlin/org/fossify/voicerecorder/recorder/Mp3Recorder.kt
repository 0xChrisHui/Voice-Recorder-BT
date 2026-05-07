package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.voicerecorder.extensions.config
import org.fossify.voicerecorder.helpers.BT_DISCONNECT_FALLBACK_MIC
import org.fossify.voicerecorder.helpers.BT_DISCONNECT_PAUSE
import org.fossify.voicerecorder.helpers.BT_DISCONNECT_STOP
import org.fossify.voicerecorder.models.Events
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * MP3 recorder built on top of [AudioRecord] + LAME.
 *
 * When [bluetoothController] is supplied (i.e. BT-priority mode is on), the recorder will:
 *  - Use the Bluetooth headset mic as input whenever a BT device is connected.
 *  - Hot-swap the input device live without stopping the LAME encoder, keeping the MP3 file continuous.
 *  - On BT disconnect, behave per [org.fossify.voicerecorder.helpers.Config.btDisconnectAction]:
 *      pause / stop / fall back to phone mic.
 *
 * Hot-swap rebuilds the [AudioRecord] on each route change (~150ms audio gap), which is more
 * reliable on MIUI than mixing setPreferredDevice with setCommunicationDevice.
 */
class Mp3Recorder(
    val context: Context,
    private val bluetoothController: BluetoothAudioController? = null
) : Recorder, BluetoothAudioController.OnRouteChangeListener {

    private val isBtPriority: Boolean = bluetoothController != null

    private val effectiveAudioSource: Int =
        if (isBtPriority) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else context.config.microphoneMode

    private val minBufferSize = AudioRecord.getMinBufferSize(
        context.config.samplingRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    private var mp3buffer: ByteArray = ByteArray(0)
    private val isPaused = AtomicBoolean(false)
    private val pausedDueToBtLoss = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    private val amplitude = AtomicInteger(0)
    private var outputPath: String? = null
    private var androidLame: AndroidLame? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null

    // Audio input: replaceable. AtomicReference lets the recording thread observe a fresh
    // instance without holding a lock during the read loop.
    private val audioRecord = AtomicReference<AudioRecord?>(null)
    private val audioRecordLock = Any()

    // Pending route changes posted from the BT listener and applied by the recording thread.
    @Volatile private var pendingPreferredDevice: AudioDeviceInfo? = null
    @Volatile private var pendingClearDevice: Boolean = false

    /**
     * Builds a fresh AudioRecord. When [routeToBt] is true, the audio framework's communication
     * device (set elsewhere via [BluetoothAudioController.requestBluetoothScoRoute]) is what
     * actually steers VOICE_COMMUNICATION input to the BT mic — we deliberately do NOT call
     * [AudioRecord.setPreferredDevice], because mixing the two routing mechanisms confuses the
     * framework on at least some MIUI builds and silently yields zero-byte reads.
     */
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(routeToBt: Boolean): AudioRecord {
        val ar = AudioRecord.Builder()
            .setAudioSource(effectiveAudioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(context.config.samplingRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .build()
        Log.d(
            TAG,
            "createAudioRecord: source=$effectiveAudioSource sampleRate=${context.config.samplingRate} " +
                "routeToBt=$routeToBt state=${ar.state}"
        )
        return ar
    }

    override fun setOutputFile(path: String) {
        outputPath = path
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        this.fileDescriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
    }

    override fun prepare() {}

    override fun start() {
        val rawData = ShortArray(minBufferSize)
        mp3buffer = ByteArray((7200 + rawData.size * 2 * 1.25).toInt())

        outputStream = try {
            if (fileDescriptor != null) {
                FileOutputStream(fileDescriptor!!.fileDescriptor)
            } else {
                FileOutputStream(File(outputPath!!))
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            return
        }

        androidLame = LameBuilder()
            .setInSampleRate(context.config.samplingRate)
            .setOutBitrate(context.config.bitrate / 1000)
            .setOutSampleRate(context.config.samplingRate)
            .setOutChannels(1)
            .build()

        // Initial input device choice: BT if connected & priority on, else default mic.
        val initialBt = if (isBtPriority) bluetoothController?.currentBluetoothInputDevice() else null
        var btRouted = false
        if (initialBt != null) {
            Log.d(TAG, "BT device present at start: ${initialBt.productName}")
            val accepted = bluetoothController!!.requestBluetoothScoRoute(initialBt)
            if (accepted) {
                btRouted = bluetoothController!!.waitForCommunicationDeviceMatch(initialBt)
                if (!btRouted) {
                    Log.w(TAG, "BT route did not become active in time, falling back to phone mic")
                }
            } else {
                Log.w(TAG, "setCommunicationDevice rejected the request")
            }
        }
        val ar = createAudioRecord(routeToBt = btRouted)
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${ar.state}); aborting")
            context.showErrorToast(IllegalStateException("AudioRecord init failed: ${ar.state}"))
            return
        }
        synchronized(audioRecordLock) {
            audioRecord.set(ar)
        }
        bluetoothController?.setListener(this)

        ensureBackgroundThread {
            try {
                audioRecord.get()?.startRecording()
                Log.d(
                    TAG,
                    "startRecording: recordingState=${audioRecord.get()?.recordingState} " +
                        "routedDevice.type=${audioRecord.get()?.routedDevice?.type}"
                )
            } catch (e: Exception) {
                context.showErrorToast(e)
                return@ensureBackgroundThread
            }
            postRouteEvent()

            var consecutiveZeroReads = 0
            while (!isStopped.get()) {
                // Apply any pending device change *before* reading more PCM.
                applyPendingSwitch()

                if (isPaused.get()) {
                    Thread.sleep(SLEEP_WHEN_PAUSED_MS)
                    continue
                }

                val live = audioRecord.get() ?: run {
                    Thread.sleep(SLEEP_WHEN_PAUSED_MS)
                    continue
                }
                val count = live.read(rawData, 0, minBufferSize)
                if (count > 0) {
                    consecutiveZeroReads = 0
                    val encoded = androidLame!!.encode(rawData, rawData, count, mp3buffer)
                    if (encoded > 0) {
                        try {
                            updateAmplitude(rawData)
                            outputStream!!.write(mp3buffer, 0, encoded)
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                } else {
                    consecutiveZeroReads++
                    // After ~1s of nothing, log loudly. After ~3s, post a routing-failure event.
                    if (consecutiveZeroReads == ZERO_READ_WARN_THRESHOLD) {
                        Log.w(
                            TAG,
                            "$ZERO_READ_WARN_THRESHOLD non-positive reads in a row " +
                                "(count=$count, source=$effectiveAudioSource, " +
                                "routed=${live.routedDevice?.type})"
                        )
                    }
                    if (consecutiveZeroReads == ZERO_READ_FAIL_THRESHOLD) {
                        Log.e(TAG, "Audio input appears dead; posting ROUTING_FAILED")
                        EventBus.getDefault().post(
                            Events.RecordingRoute(
                                Events.RecordingRoute.ROUTING_FAILED,
                                bluetoothController?.currentBluetoothInputDevice()?.productName?.toString()
                            )
                        )
                    }
                    Thread.sleep(SLEEP_WHEN_NO_DATA_MS)
                }
            }
        }
    }

    private fun applyPendingSwitch() {
        val newDevice: AudioDeviceInfo?
        val isClear: Boolean
        synchronized(audioRecordLock) {
            if (pendingPreferredDevice == null && !pendingClearDevice) return
            newDevice = pendingPreferredDevice
            isClear = pendingClearDevice
            pendingPreferredDevice = null
            pendingClearDevice = false
        }

        Log.d(TAG, "applyPendingSwitch: clear=$isClear newDevice=${newDevice?.productName}")

        // Step 1: re-route the audio framework's communication device.
        val targetIsBt = !isClear && newDevice != null
        if (targetIsBt) {
            bluetoothController?.requestBluetoothScoRoute(newDevice!!)
            bluetoothController?.waitForCommunicationDeviceMatch(newDevice)
        } else {
            bluetoothController?.releaseBluetoothScoRoute()
        }

        // Step 2: rebuild the AudioRecord so the new route actually takes effect.
        // We always rebuild rather than mixing setPreferredDevice with setCommunicationDevice;
        // the latter combination silently fails on some MIUI builds.
        val current = audioRecord.get() ?: return
        synchronized(audioRecordLock) {
            try {
                current.stop()
            } catch (_: Exception) {
            }
            current.release()
            val rebuilt = createAudioRecord(routeToBt = targetIsBt)
            try {
                rebuilt.startRecording()
            } catch (e: Exception) {
                context.showErrorToast(e)
            }
            audioRecord.set(rebuilt)
            Log.d(
                TAG,
                "applyPendingSwitch done: state=${rebuilt.recordingState} " +
                    "routed=${rebuilt.routedDevice?.type}"
            )
        }
        postRouteEvent()
    }

    private fun isBluetoothInputType(d: AudioDeviceInfo?): Boolean {
        if (d == null) return false
        if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) return true
        return false
    }

    private fun postRouteEvent() {
        val ar = audioRecord.get() ?: return
        val routed = ar.routedDevice
        val want = if (isBtPriority) bluetoothController?.currentBluetoothInputDevice() else null

        val event = when {
            isBtPriority && want != null && !isBluetoothInputType(routed) ->
                Events.RecordingRoute(Events.RecordingRoute.ROUTING_FAILED, want.productName?.toString())
            isBluetoothInputType(routed) ->
                Events.RecordingRoute(Events.RecordingRoute.BLUETOOTH, routed?.productName?.toString())
            isPaused.get() && pausedDueToBtLoss.get() ->
                Events.RecordingRoute(Events.RecordingRoute.WAITING_FOR_BT, null)
            else ->
                Events.RecordingRoute(Events.RecordingRoute.PHONE_MIC, null)
        }
        EventBus.getDefault().post(event)
    }

    override fun stop() {
        isPaused.set(true)
        isStopped.set(true)
        try {
            audioRecord.get()?.stop()
        } catch (_: Exception) {
        }
        bluetoothController?.setListener(null)
    }

    override fun pause() {
        // User-initiated pause; clear BT-loss flag so a BT reconnect doesn't auto-resume.
        pausedDueToBtLoss.set(false)
        isPaused.set(true)
    }

    override fun resume() {
        pausedDueToBtLoss.set(false)
        isPaused.set(false)
    }

    override fun release() {
        androidLame?.flush(mp3buffer)
        outputStream?.close()
        try {
            audioRecord.get()?.release()
        } catch (_: Exception) {
        }
    }

    override fun getMaxAmplitude(): Int = amplitude.get()

    private fun updateAmplitude(data: ShortArray) {
        var sum = 0L
        for (i in 0 until minBufferSize step 2) {
            sum += abs(data[i].toInt())
        }
        amplitude.set((sum / (minBufferSize / 8)).toInt())
    }

    // -- BluetoothAudioController.OnRouteChangeListener --

    override fun onBluetoothConnected(device: AudioDeviceInfo) {
        if (!isBtPriority) return
        pendingPreferredDevice = device
        // If we paused because BT was lost, auto-resume now that it's back.
        if (pausedDueToBtLoss.get()) {
            pausedDueToBtLoss.set(false)
            isPaused.set(false)
        }
    }

    override fun onBluetoothDisconnected() {
        if (!isBtPriority) return
        when (context.config.btDisconnectAction) {
            BT_DISCONNECT_PAUSE -> {
                pausedDueToBtLoss.set(true)
                isPaused.set(true)
                postRouteEvent()
            }
            BT_DISCONNECT_STOP -> {
                isStopped.set(true)
            }
            BT_DISCONNECT_FALLBACK_MIC -> {
                pendingClearDevice = true
            }
        }
    }

    companion object {
        private const val TAG = "Mp3Recorder"
        private const val SLEEP_WHEN_PAUSED_MS = 50L
        private const val SLEEP_WHEN_NO_DATA_MS = 20L
        // ~20ms per read at 48kHz mono. 50 zero-reads ≈ 1 second of nothing.
        private const val ZERO_READ_WARN_THRESHOLD = 50
        private const val ZERO_READ_FAIL_THRESHOLD = 150
    }
}
