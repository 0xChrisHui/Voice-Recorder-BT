package org.fossify.voicerecorder.helpers

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import org.fossify.voicerecorder.extensions.config
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tee-style logger that writes to both adb's logcat and a plain-text file in the user's
 * recordings folder. Lets us diagnose BT routing problems on devices the user can't easily
 * attach to with ADB — they just stop the recording and look for `bt-debug.log` next to their
 * recordings, then send us the file.
 */
object BtLog {
    private const val LOG_NAME = "bt-debug.log"

    private var writer: PrintWriter? = null
    private val lock = Any()
    private val timestampFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            close()
            try {
                val dir = File(context.config.saveRecordingsFolder)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, LOG_NAME)
                writer = PrintWriter(FileWriter(file, true), true)
                val started = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                writer?.println("")
                writer?.println("=== Session start $started ===")
                writer?.println("device.manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}")
                writer?.println("device.sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
            } catch (e: Exception) {
                Log.w("BtLog", "init failed", e)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        write("D", tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(tag, msg, t)
        val text = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
        write("W", tag, text)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        val text = if (t != null) "$msg\n${Log.getStackTraceString(t)}" else msg
        write("E", tag, text)
    }

    /**
     * Dumps the current view of the audio framework — which input devices are present, which
     * communication devices the platform offers, the active comm device, and the current audio
     * mode. Run this at the start of recording to capture the snapshot for diagnosis.
     */
    fun dumpDeviceSnapshot(audioManager: AudioManager) {
        d("Snapshot", "=== Device snapshot ===")
        try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach {
                d(
                    "Snapshot",
                    "INPUT: type=${it.type} name='${it.productName}' id=${it.id} sink=${it.isSink}"
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices.forEach {
                    d(
                        "Snapshot",
                        "COMM_AVAIL: type=${it.type} name='${it.productName}' id=${it.id}"
                    )
                }
                val cur = audioManager.communicationDevice
                d(
                    "Snapshot",
                    "currentCommDevice: type=${cur?.type} name='${cur?.productName}' id=${cur?.id}"
                )
            }
            d("Snapshot", "audioManager.mode=${audioManager.mode} (NORMAL=0, IN_CALL=2, IN_COMMUNICATION=3)")
        } catch (e: Exception) {
            w("Snapshot", "Snapshot failed", e)
        }
    }

    private fun write(level: String, tag: String, msg: String) {
        synchronized(lock) {
            try {
                writer?.println("${timestampFmt.format(Date())} $level/$tag: $msg")
            } catch (_: Exception) {
            }
        }
    }
}
