package org.fossify.voicerecorder.models

import android.net.Uri

class Events {
    class RecordingDuration internal constructor(val duration: Int)
    class RecordingStatus internal constructor(val status: Int)
    class RecordingAmplitude internal constructor(val amplitude: Int)
    class RecordingCompleted internal constructor()
    class RecordingTrashUpdated internal constructor()
    class RecordingSaved internal constructor(val uri: Uri?)

    // Bluetooth-only mode events.
    // routeKind: one of RouteKind.* below.
    // deviceName: human-readable name of the active BT device, or null when on phone mic / none.
    class RecordingRoute internal constructor(val routeKind: Int, val deviceName: String?) {
        companion object {
            const val PHONE_MIC = 0
            const val BLUETOOTH = 1
            const val WAITING_FOR_BT = 2
            const val ROUTING_FAILED = 3
        }
    }
}
