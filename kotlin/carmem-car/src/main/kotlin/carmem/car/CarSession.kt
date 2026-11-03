package carmem.car

import carmem.CarMemory
import carmem.HARD_SIGNAL_KINDS
import carmem.IdentitySignal
import carmem.Occupancy
import carmem.Person
import carmem.Proposal
import carmem.Trip
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.LocalDateTime

/**
 * Drives a [CarMemory] from the stream of [CarEvent]s a head unit produces.
 *
 * Owns the glue that is easy to get wrong on device: identity signals arrive
 * before and around ignition, so they are buffered and resolved at ignition on;
 * an unknown seat profile enrolls a new driver, and phones / fobs seen with a
 * recognised driver are bound to them; proactive proposals are computed once
 * the car knows where it is; trips come from the segmenter and are recorded
 * against the current driver; place renames (inferred `home`/`work`, or a
 * driver naming a place) are pushed into memory so routines follow the place;
 * guests are wiped at ignition off by the memory itself.
 *
 * With [asyncExtraction], utterances are logged and intents handled at once, and
 * extraction runs on an [ExtractionQueue] whose results are applied through
 * [runOnMemoryThread] (the thread that calls [onEvent]).
 */
class CarSession(
    val memory: CarMemory,
    val places: PlaceResolver = PlaceResolver(),
    private val signalTtl: Duration = Duration.ofMinutes(10),
    asyncExtraction: Boolean = false,
    runOnMemoryThread: (() -> Unit) -> Unit = { it() },
    private val onDriver: (Occupancy, Person?) -> Unit = { _, _ -> },
    private val onProposals: (List<Proposal>) -> Unit = {},
) : AutoCloseable {
    private val pendingSignals = mutableListOf<OccupantObserved>()
    private var sessionOpen = false
    private var proposedThisSession = false
    private var lastPlace: Place? = null
    private var eventTs: LocalDateTime? = null

    val segmenter = TripSegmenter(places) { trip -> onTrip(trip) }

    /** Background extraction, when [asyncExtraction] is on. */
    val extraction: ExtractionQueue? = if (asyncExtraction) ExtractionQueue(memory, runOnMemoryThread) else null

    init {
        places.addRenameListener { _, old, new -> memory.renamePlace(null, old, new, eventTs ?: memory.clock()) }
    }

    fun onEvent(e: CarEvent) {
        eventTs = e.ts
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
            is UtteranceHeard -> if (sessionOpen) hear(e)
            is VehicleFault -> memory.recordVehicleEvent(e.key, e.value, now = e.ts)
            is PlaceNamed -> lastPlace?.let { places.label(it, e.name) }
        }
    }

    private fun hear(e: UtteranceHeard) {
        val q = extraction ?: run { memory.hear(e.text, e.speakerId, e.ts); return }
        val r = memory.hear(e.text, e.speakerId, e.ts, extract = false)
        if ((r["deferred"] as? JsonPrimitive)?.booleanOrNull == true)
            q.submit((r["episode"] as JsonPrimitive).longOrNull!!, (r["scope"] as JsonPrimitive).content, e.text, e.ts)
    }

    private fun open(ts: LocalDateTime) {
        resolveDriver(ts)
        sessionOpen = true; proposedThisSession = false
        if (lastPlace != null) propose(ts)
    }

    private fun reopen(ts: LocalDateTime) {
        // Hard driver signal changed mid-session (fob handed over at a stop): re-resolve without losing pending trip state.
        resolveDriver(ts)
        proposedThisSession = false
    }

    /**
     * Resolve the driver; enroll an unknown seat profile (policy.auto_enroll_kinds) instead of
     * opening a guest session; bind co-present unbound phones / fobs (policy.auto_bind_kinds)
     * to a recognised driver. Voice is never enrolled or bound here.
     */
    private fun resolveDriver(ts: LocalDateTime): Occupancy {
        val signals = pendingSignals.filter { Duration.between(it.ts, ts).abs() <= signalTtl }.map { it.signal }.distinct()
        val policy = memory.policy
        fun unbound(s: IdentitySignal) = s.role == "driver" && s.kind != "voice" && s.kind in HARD_SIGNAL_KINDS && memory.store.lookupIdentity(s.kind, s.value) == null
        if (memory.identity.peekDriver(signals) == null) {
            signals.firstOrNull { unbound(it) && it.kind in policy.auto_enroll_kinds }?.let { s ->
                val id = "${s.kind}:${s.value}"
                if (memory.store.getPerson(id) == null) memory.enroll(id, "Driver ${s.value}", listOf(s.kind to s.value)) else memory.bind(s.kind, s.value, id)
                memory.store.audit(ts, id, "identity.auto_enrolled", buildJsonObject { put("kind", s.kind); put("value", s.value) })
            }
        }
        val occ = memory.ignitionOn(signals, ts)
        if (!occ.isGuest && memory.identity.boundDrivers(signals) == setOf(occ.driverId)) {
            for (s in signals) if (unbound(s) && s.kind in policy.auto_bind_kinds) {
                memory.bind(s.kind, s.value, occ.driverId)
                memory.store.audit(ts, occ.driverId, "identity.auto_bound", buildJsonObject { put("kind", s.kind); put("value", s.value) })
            }
        }
        onDriver(occ, if (occ.isGuest) null else memory.store.getPerson(occ.driverId))
        return occ
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
    fun answer(yes: Boolean, ts: LocalDateTime = memory.clock()) = memory.correct(if (yes) "yes" else "no", ts)

    fun currentSignals(): List<IdentitySignal> = pendingSignals.map { it.signal }

    /** Stops background extraction, if any. Does not close the memory. */
    override fun close() { extraction?.close() }
}
