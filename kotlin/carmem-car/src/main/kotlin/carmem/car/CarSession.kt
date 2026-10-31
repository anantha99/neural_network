package carmem.car

import carmem.CarMemory
import carmem.IdentitySignal
import carmem.Proposal
import carmem.Trip
import java.time.Duration
import java.time.LocalDateTime

/**
 * Drives a [CarMemory] from the stream of [CarEvent]s a head unit produces.
 *
 * Owns the glue that is easy to get wrong on device: identity signals arrive
 * before and around ignition, so they are buffered and resolved at ignition on;
 * proactive proposals are computed once the car knows where it is; trips come
 * from the segmenter and are recorded against the current driver; guests are
 * wiped at ignition off by the memory itself.
 */
class CarSession(
    val memory: CarMemory,
    val places: PlaceResolver = PlaceResolver(),
    private val signalTtl: Duration = Duration.ofMinutes(10),
    private val onProposals: (List<Proposal>) -> Unit = {},
) {
    private val pendingSignals = mutableListOf<OccupantObserved>()
    private var sessionOpen = false
    private var proposedThisSession = false
    private var lastPlace: Place? = null

    val segmenter = TripSegmenter(places) { trip -> onTrip(trip) }

    fun onEvent(e: CarEvent) {
        when (e) {
            is OccupantObserved -> {
                pendingSignals.removeAll { Duration.between(it.ts, e.ts) > signalTtl }
                pendingSignals += e
                // a passenger phone appearing mid-trip is informational; a driver signal mid-trip re-resolves
                if (sessionOpen && e.signal.role == "driver" && e.signal.kind != "voice") reopen(e.ts)
            }
            is IgnitionChanged -> {
                segmenter.onEvent(e)
                if (e.on) open(e.ts) else close(e.ts)
            }
            is LocationFix -> {
                segmenter.onEvent(e)
                lastPlace = places.resolve(e.lat, e.lon)
                if (sessionOpen && !proposedThisSession) propose(e.ts)
            }
            is SettingChanged -> segmenter.onEvent(e)
            is UtteranceHeard -> if (sessionOpen) memory.hear(e.text, e.speakerId, e.ts)
            is VehicleFault -> memory.recordVehicleEvent(e.key, e.value, now = e.ts)
            is PlaceNamed -> lastPlace?.let { places.label(it, e.name) }
        }
    }

    private fun open(ts: LocalDateTime) {
        val signals = pendingSignals.filter { Duration.between(it.ts, ts).abs() <= signalTtl }.map { it.signal }.distinct()
        memory.ignitionOn(signals, ts)
        sessionOpen = true; proposedThisSession = false
        if (lastPlace != null) propose(ts)
    }

    private fun reopen(ts: LocalDateTime) {
        // Hard driver signal changed mid-session (fob handed over at a stop): re-resolve without losing pending trip state.
        val signals = pendingSignals.filter { Duration.between(it.ts, ts).abs() <= signalTtl }.map { it.signal }.distinct()
        memory.ignitionOn(signals, ts)
        proposedThisSession = false
    }

    private fun close(ts: LocalDateTime) {
        if (!sessionOpen) return
        memory.ignitionOff(ts)
        sessionOpen = false
        pendingSignals.clear()
    }

    private fun propose(ts: LocalDateTime) {
        proposedThisSession = true
        val props = memory.proactive(ts, lastPlace?.name)
        if (props.isNotEmpty()) onProposals(props)
    }

    private fun onTrip(trip: Trip) {
        if (sessionOpen || memory.occupancy != null) memory.recordTrip(trip, trip.end)
        // allow the next leg (chained trip) to get its own proposal from the new place
        proposedThisSession = false
    }

    /** Convenience for voice front-ends: a yes/no to the last proposal. */
    fun answer(yes: Boolean, ts: LocalDateTime) = memory.correct(if (yes) "yes" else "no", ts)

    fun currentSignals(): List<IdentitySignal> = pendingSignals.map { it.signal }
}
