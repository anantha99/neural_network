package carmem.android

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import carmem.car.CarEvent
import carmem.car.LocationFix
import java.time.LocalDateTime

/** Platform location -> LocationFix every ~30 s / 50 m, which is all trip segmentation needs. */
class LocationFeed(private val context: Context, private val sink: (CarEvent) -> Unit) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val listener = LocationListener { loc: Location ->
        sink(LocationFix(LocalDateTime.now(), loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed.toDouble() else null))
    }

    fun start() {
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 50f, listener, Looper.getMainLooper())
        }.onFailure { android.util.Log.w(CarMemoryService.TAG, "location unavailable: ${it.message}") }
    }

    fun stop() = lm.removeUpdates(listener)
}
