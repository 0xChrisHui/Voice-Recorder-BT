package org.fossify.voicerecorder.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
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
 * The hot-swap is best-effort: it first tries [AudioRecord.setPreferredDevice] (no audio gap),
 * and on failure rebuilds the [AudioRecord] (~150ms gap).
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

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(preferredDevice: AudioDeviceInfo?): AudioRecord {
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
        if (preferredDevice != null) {
            ar.preferredDevice = preferredDevice
        }
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
        if (initialBt != null) {
            bluetoothController?.requestBluetoothScoRoute(initialBt)
        }
        synchronized(audioRecordLock) {
            audioRecord.set(createAudioRecord(initialBt))
        }
        bluetoothController?.setListener(this)

        ensureBackgroundThread {
            try {
                audioRecord.get()?.startRecording()
            } catch (e: Exception) {
                context.showErrorToast(e)
                return@ensureBackgroundThread
            }
            postRouteEvent()

            while (!isStopped.get()) {
                // Apply any pending device change *before* reading more PCM.
                applyPendingSwitch()

                if (isPaused.get()) {
                    Thread.sleep(SLEEP_WHEN_PAUSED_MS)
                    continue
                }

                val ar = audioRecord.get() ?: run {
                    Thread.sleep(SLEEP_WHEN_PAUSED_MS)
                    continue
                }
                val count = ar.read(rawData, 0, minBufferSize)
                if (count > 0) {
                    val encoded = androidLame!!.encode(rawData, rawData, count, mp3buffer)
                    if (encoded > 0) {
                        try {
                            updateAmplitude(rawData)
                            outputStream!!.write(mp3buffer, 0, encoded)
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
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

        val current = audioRecord.get() ?: return
        val target: AudioDeviceInfo? = if (isClear) null else newDevice

        // Approach A: try non-disruptive setPreferredDevice.
        val accepted = try {
            current.setPreferredDevice(target)
        } catch (e: Exception) {
            false
        }
        // Give the audio framework a moment to actually re-route.
        Thread.sleep(SETPREFERRED_VERIFY_DELAY_MS)
        val routed = current.routedDevice
        val matched = if (target == null) {
            !isBluetoothInputType(routed)
        } else {
            routed?.id == target.id
        }
        if (accepted && matched) {
            postRouteEvent()
            return
        }

        // Approach B: rebuild the AudioRecord. Brief audio gap (~150ms).
        synchronized(audioRecordLock) {
            try {
                current.stop()
            } catch (_: Exception) {
            }
            current.release()
            val rebuilt = createAudioRecord(target)
            try {
                rebuilt.startRecording()
            } catch (e: Exception) {
                context.showErrorToast(e)
            }
            audioRecord.set(rebuilt)
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
        val expected = pendingPreferredDevice
        val want = if (isBtPriority) bluetoothController?.currentBluetoothInputDevice() else null

        val event = when {
            isBtPriority && want != null && !isBluetoothInputType(routed) ->
                Events.RecordingRoute(Events.RecordingRoute.Companion.ROUTING_FAILED, want.productName?.toString())
            isBluetoothInputType(routed) ->
                Events.RecordingRoute(Events.RecordingRoute.Companion.BLUETOOTH, routed?.productName?.toString())
            isPaused.get() && pausedDueToBtLoss.get() ->
                Events.RecordingRoute(Events.RecordingRoute.Companion.WAITING_FOR_BT, null)
            else ->
                Events.RecordingRoute(Events.RecordingRoute.Companion.PHONE_MIC, null)
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
        private const val SLEEP_WHEN_PAUSED_MS = 50L
        private const val SETPREFERRED_VERIFY_DELAY_MS = 150L
    }
}
