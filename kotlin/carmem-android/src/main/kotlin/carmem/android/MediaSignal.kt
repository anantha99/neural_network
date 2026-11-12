package carmem.android

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import carmem.car.CarEvent
import carmem.car.SettingChanged
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime

/**
 * What is playing -> a "media" setting, so a habit ("jazz on the way to work") is learned from what the driver
 * plays rather than only from what they say. The value is the genre when the source reports one, else the name of
 * the app that is playing; titles and artists are not recorded.
 *
 * Needs MEDIA_CONTENT_CONTROL, which is privileged like the vehicle permissions. Start it on a thread with a looper.
 */
class MediaSignal(private val context: Context, private val sink: (CarEvent) -> Unit) {
    private val manager: MediaSessionManager? = context.getSystemService(MediaSessionManager::class.java)
    private var controller: MediaController? = null
    private var last: String? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
        override fun onPlaybackStateChanged(state: PlaybackState?) = emit()
    }
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> attach(controllers?.firstOrNull()) }

    fun start() {
        runCatching {
            val m = manager ?: return
            m.addOnActiveSessionsChangedListener(listener, null)
            attach(m.getActiveSessions(null).firstOrNull())
        }.onFailure { Log.i(CarMemoryService.TAG, "media sessions unavailable: ${it.message}") }
    }

    private fun attach(c: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback)
        emit()
    }

    private fun emit() {
        val c = controller ?: return
        if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return
        val genre = c.metadata?.getString(MediaMetadata.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() }
        val source = genre ?: runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        if (source == last) return
        last = source
        sink(SettingChanged(LocalDateTime.now(), "media", JsonPrimitive(source)))
    }

    fun stop() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(listener); controller?.unregisterCallback(callback) }
    }
}
