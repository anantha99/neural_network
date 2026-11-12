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
 *
 * Places live in the memory database (restored here, saved after every trip, rename and
 * ignition off), so a crash or power cut loses at most the trip in progress.
 *
 * With [autoEnrollProfiles] a driver's Android user profile (a `seat_profile` signal) is enrolled
 * the first time it is seen. Without that nobody is ever enrolled on a head unit, every driver is a
 * guest, and guests form no memory. Platform guest users send no such signal and stay guests.
 */
class CarSession(
    val memory: CarMemory,
    val places: PlaceResolver = PlaceResolver(),
    private val signalTtl: Duration = Duration.ofMinutes(10),
    private val autoEnrollProfiles: Boolean = true,
    private val onProposals: (List<Proposal>) -> Unit = {},
) {
    private val pendingSignals = mutableListOf<OccupantObserved>()
    private var sessionOpen = false
    private var proposedThisSession = false
    private var lastFix: LocationFix? = null
    private var lastPlace: Place? = null

    val segmenter = TripSegmenter(places) { trip -> onTrip(trip) }

    /** Whether the car was moving at the last fix; proposals for [carmem.Policy.act_blocked_while_moving] are held back while it is. */
    val moving: Boolean get() = segmenter.moving

    /** Where the car is, as proposals want it: a known place's name, a name that matches no routine when the spot is new, null before the first fix. */
    val location: String? get() = if (lastFix == null) null else lastPlace?.name ?: UNKNOWN_PLACE

    init {
        memory.store.getMeta(PLACES_KEY)?.let(places::restore)
        places.onRenamed = { old, new, at -> memory.renamePlace(old, new, at); persistPlaces() }
    }

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
                // the segmenter has just made a place of wherever the car is parked, if it was not one already
                lastFix?.let { lastPlace = places.find(it.lat, it.lon) }
                memory.carMoving = moving; memory.carLocation = location
                if (e.on) open(e.ts) else close(e.ts)
            }
            is LocationFix -> {
                segmenter.onEvent(e)
                lastFix = e
                // Look the place up without creating one: a fix on the road is not a place, and a new place per fix
                // would grow the place list (and the cost of every lookup) with every kilometre driven.
                // The segmenter creates places where trips start and end.
                lastPlace = places.find(e.lat, e.lon)
                memory.carMoving = moving; memory.carLocation = location
                if (sessionOpen && !proposedThisSession) propose(e.ts)
            }
            is SettingChanged -> segmenter.onEvent(e)
            is UtteranceHeard -> if (sessionOpen) memory.hear(e.text, e.speakerId, e.ts)
            is VehicleFault -> memory.recordVehicleEvent(e.key, e.value, now = e.ts)
            is PlaceNamed -> lastFix?.let { f -> places.label(places.resolve(f.lat, f.lon).also { lastPlace = it }, e.name, e.ts); persistPlaces() }
        }
    }

    /** Fresh driver signals, enrolling an Android user profile seen for the first time. */
    private fun signalsAt(ts: LocalDateTime): List<IdentitySignal> {
        val signals = pendingSignals.filter { Duration.between(it.ts, ts).abs() <= signalTtl }.map { it.signal }.distinct()
        if (autoEnrollProfiles) for (s in signals) {
            if (s.kind != "seat_profile" || s.role != "driver" || memory.store.lookupIdentity(s.kind, s.value) != null) continue
            memory.enroll(s.value, "Driver ${s.value.substringAfter(':')}", listOf(s.kind to s.value))
        }
        return signals
    }

    private fun open(ts: LocalDateTime) {
        memory.ignitionOn(signalsAt(ts), ts)
        sessionOpen = true; proposedThisSession = false
        if (lastFix != null) propose(ts)
    }

    private fun reopen(ts: LocalDateTime) {
        // Hard driver signal changed mid-session (fob handed over at a stop): re-resolve without losing pending trip state.
        memory.ignitionOn(signalsAt(ts), ts)
        proposedThisSession = false
    }

    private fun close(ts: LocalDateTime) {
        if (!sessionOpen) return
        memory.ignitionOff(ts)
        sessionOpen = false
        pendingSignals.clear()
        persistPlaces()
    }

    private fun propose(ts: LocalDateTime) {
        proposedThisSession = true
        val props = memory.proactive(ts, location, moving)
        if (props.isNotEmpty()) onProposals(props)
    }

    private fun onTrip(trip: Trip) {
        if (sessionOpen || memory.occupancy != null) memory.recordTrip(trip, trip.end)
        persistPlaces()
        // allow the next leg (chained trip) to get its own proposal from the new place
        proposedThisSession = false
    }

    /** Save the place list into the memory database. Called on every change that matters; safe to call any time. */
    fun persistPlaces() = memory.store.putMeta(PLACES_KEY, places.snapshot())

    /** Convenience for voice front-ends: a yes/no to the last proposal. */
    fun answer(yes: Boolean, ts: LocalDateTime) = memory.correct(if (yes) "yes" else "no", ts)

    fun currentSignals(): List<IdentitySignal> = pendingSignals.map { it.signal }

    companion object {
        const val PLACES_KEY = "places"
        /** Passed as the location when the car is somewhere it has no place for: routines that start at a known place stay quiet. */
        const val UNKNOWN_PLACE = "(unknown place)"
    }
}
