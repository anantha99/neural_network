package carmem.car

import carmem.Trip
import kotlinx.serialization.json.JsonElement
import java.time.Duration
import java.time.LocalDateTime

/**
 * Turns ignition, location and setting events into [Trip]s.
 *
 * A trip starts at ignition on (or when the car moves again after a dwell) and
 * ends at ignition off, or when the car has been stationary for [dwellMinutes]
 * with the engine running (school drop-off, drive-through). That dwell split is
 * what makes "school at 07:40 then office at 08:05" two trips, which the
 * consolidator's chained-trip handling expects.
 */
class TripSegmenter(
    private val places: PlaceResolver,
    private val dwellMinutes: Long = 8,
    private val minTripMeters: Double = 300.0,
    private val stationarySpeedMps: Double = 1.0,
    private val onTrip: (Trip) -> Unit,
) {
    private var ignitionOn = false
    private var tripStart: LocalDateTime? = null
    private var origin: Place? = null
    private var lastFix: LocationFix? = null
    private var movedMeters = 0.0
    private var stationarySince: LocalDateTime? = null
    private var settings = linkedMapOf<String, JsonElement>()
    private var tollsSeen = false
    private var dwellingAt: Place? = null
    private var dwellStart: LocalDateTime? = null

    val currentPlace: Place? get() = lastFix?.let { places.resolve(it.lat, it.lon) }

    fun onEvent(e: CarEvent) {
        when (e) {
            is IgnitionChanged -> if (e.on) start(e.ts) else stop(e.ts)
            is LocationFix -> fix(e)
            is SettingChanged -> if (ignitionOn) settings[e.key] = e.value
            else -> Unit
        }
    }

    fun markTollRoad() { tollsSeen = true }

    private fun start(ts: LocalDateTime) {
        ignitionOn = true
        tripStart = ts; movedMeters = 0.0; stationarySince = null; settings = linkedMapOf(); tollsSeen = false
        origin = lastFix?.let { places.resolve(it.lat, it.lon) }
        dwellingAt?.let { p -> dwellStart?.let { places.dwell(p, it, ts) } }
        dwellingAt = null; dwellStart = null
    }

    private fun stop(ts: LocalDateTime) {
        if (!ignitionOn) return
        ignitionOn = false
        closeTrip(ts)
        dwellingAt = lastFix?.let { places.resolve(it.lat, it.lon) }; dwellStart = ts
    }

    private fun fix(f: LocationFix) {
        val prev = lastFix
        lastFix = f
        if (!ignitionOn) return
        if (origin == null) origin = places.resolve(f.lat, f.lon)
        val step = prev?.let { haversineMeters(it.lat, it.lon, f.lat, f.lon) } ?: 0.0
        val moving = (f.speedMps ?: (step / maxOf(1.0, prev?.let { Duration.between(it.ts, f.ts).seconds.toDouble() } ?: 1.0))) > stationarySpeedMps
        if (moving) {
            movedMeters += step
            if (stationarySince != null && tripStart == null) { // moving again after a dwell split: new trip
                tripStart = f.ts; movedMeters = step; settings = linkedMapOf(); tollsSeen = false
                origin = places.resolve(prev!!.lat, prev.lon)
            }
            stationarySince = null
        } else {
            if (stationarySince == null) stationarySince = f.ts
            else if (tripStart != null && movedMeters >= minTripMeters && Duration.between(stationarySince, f.ts).toMinutes() >= dwellMinutes) {
                closeTrip(stationarySince!!)
                tripStart = null // next movement opens a new trip
            }
        }
    }

    private fun closeTrip(end: LocalDateTime) {
        val start = tripStart ?: return
        val fixNow = lastFix ?: return
        val dest = places.resolve(fixNow.lat, fixNow.lon)
        val org = origin ?: dest
        if (movedMeters >= minTripMeters && dest.id != org.id) {
            onTrip(Trip(start, end, org.name, dest.name, settings = settings.toMap(),
                route = mapOf("tolls" to kotlinx.serialization.json.JsonPrimitive(tollsSeen)), distanceKm = movedMeters / 1000.0))
        }
        tripStart = null; movedMeters = 0.0; origin = dest
    }
}
