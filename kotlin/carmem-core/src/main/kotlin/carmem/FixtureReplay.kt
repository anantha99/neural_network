package carmem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Replays a driver-log fixture exported by the Python reference (bench/export_fixtures.py)
 * into a live [CarMemory], using the fixture's own timestamps and the `replay_protocol` of
 * fixtures/wire-schema.json. Used by the conformance test and to seed a demo device with
 * weeks of history in seconds.
 *
 * The caller owns [memory]: if the fixture carries a `policy` object, build the memory with
 * `Policy.fromJson(fixture["policy"].toString())` before replaying; [replay] never rebuilds it.
 *
 * Re-running [replay] on the same memory is safe (persons already in the store are skipped,
 * nothing throws), but it is not a no-op: every event is applied again, so observed routines
 * and facts accumulate further evidence (routine support may double-count) and the
 * proposals/corrections logged on the second pass will differ from the first.
 */
class FixtureReplay(val memory: CarMemory) {
    /** One routine proposal seen during replay, as the protocol logs it (ts = trip start). */
    data class LoggedProposal(val ts: String, val scope: String, val dest: String, val tier: String, val correct: Boolean, val week: Int)

    /**
     * @property events number of events replayed
     * @property proposals routine proposals in the order they were made
     * @property noSaid destination -> how many times "no" was said to it
     * @property forgetDone destination (or forget entry) -> week it was forgotten
     * @property lastTs timestamp of the last event (ignition went off at lastTs + 1 h)
     * @property persons ids of the fixture's persons (enrolled now or already present)
     */
    data class Result(
        val events: Int,
        val proposals: List<LoggedProposal>,
        val noSaid: Map<String, Int>,
        val forgetDone: Map<String, Int>,
        val lastTs: LocalDateTime,
        val persons: List<String>,
    )

    /** Enroll the fixture's persons (skipping ones already present), replay every event with the wire protocol, ignition off at the end. */
    fun replay(fixtureJson: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result =
        replay(JSON.parseToJsonElement(fixtureJson).jsonObject, onProgress)

    /** As [replay] for an already-parsed fixture. */
    fun replay(fixture: JsonObject, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val mem = memory
        val persons = fixture["persons"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        for (p in persons) {
            val id = p["id"]!!.jsonPrimitive.content
            if (mem.store.getPerson(id) != null) continue
            mem.enroll(id, p["name"]!!.jsonPrimitive.content, p["signals"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content })
        }

        val events = fixture["events"]!!.jsonArray.map { it.jsonObject }
        require(events.isNotEmpty()) { "fixture has no events" }
        val logged = mutableListOf<LoggedProposal>()
        val noSaid = linkedMapOf<String, Int>()
        val forgetDone = linkedMapOf<String, Int>()
        var current: Pair<String, LocalDate>? = null
        var lastTs: LocalDateTime = parseIso(events.first()["ts"]!!.jsonPrimitive.content)

        for ((i, ev) in events.withIndex()) {
            val ts = parseIso(ev["ts"]!!.jsonPrimitive.content); lastTs = ts
            val type = ev["type"]!!.jsonPrimitive.content
            val driver = ev["driver"]!!.jsonPrimitive.content
            val week = ev["week"]?.jsonPrimitive?.int ?: 0
            when (type) {
                "sensor" -> {
                    val pl = ev["payload"]!!.jsonObject
                    mem.recordVehicleEvent(pl["key"]!!.jsonPrimitive.content, pl["value"]!!, now = ts)
                }
                else -> {
                    val key = driver to ts.toLocalDate()
                    if (key != current) {
                        if (current != null) mem.ignitionOff(ts.minusMinutes(1))
                        mem.ignitionOn(signalsFor(fixture, driver), ts.minusMinutes(5)); current = key
                    }
                    if (type == "say") mem.hear(ev["text"]!!.jsonPrimitive.content, now = ts)
                    else replayTrip(ev, week, logged, noSaid, forgetDone)
                }
            }
            onProgress(i + 1, events.size)
        }
        mem.ignitionOff(lastTs.plusHours(1))
        return Result(events.size, logged.toList(), noSaid.toMap(), forgetDone.toMap(), lastTs, persons.map { it["id"]!!.jsonPrimitive.content })
    }

    private fun replayTrip(ev: JsonObject, week: Int, logged: MutableList<LoggedProposal>, noSaid: MutableMap<String, Int>, forgetDone: MutableMap<String, Int>) {
        val mem = memory
        val trip = tripOf(ev["trip"]!!.jsonObject)
        val reject = ev["reject_if_proposed"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val forget = ev["forget_if_proposed"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val answerAt = trip.start.minusMinutes(1)
        for (p in mem.proactive(trip.start.minusMinutes(2), trip.origin)) {
            if (p.kind != "routine") continue
            val dest = p.action["destination"]!!.render()
            logged += LoggedProposal(iso(trip.start), p.scope, dest, p.tier.wire, normPlace(dest) == normPlace(trip.destination), week)
            if (reject.any { normPlace(it) == normPlace(dest) }) { mem.hear("no", now = answerAt); noSaid[dest] = (noSaid[dest] ?: 0) + 1 }
            if (forget.any { normPlace(it) in normPlace(dest) }) { mem.hear("forget that", now = answerAt); forgetDone[dest] = week }
        }
        if (forget.isNotEmpty() && forgetDone.keys.none { k -> forget.any { normPlace(it) in normPlace(k) } }) {
            mem.hear("forget that I go to the ${forget[0]}", now = answerAt); forgetDone[forget[0]] = week
        }
        mem.recordTrip(trip, trip.end)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Identity signals for a fixture driver id; "guest" uses the fixture's `guest_signal`. */
        fun signalsFor(fixture: JsonObject, driver: String): List<IdentitySignal> {
            if (driver == "guest") {
                val g = fixture["guest_signal"]!!.jsonObject
                return listOf(IdentitySignal(g["kind"]!!.jsonPrimitive.content, g["value"]!!.jsonPrimitive.content, g["confidence"]?.jsonPrimitive?.double ?: 1.0))
            }
            val p = fixture["persons"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["id"]!!.jsonPrimitive.content == driver }
                ?: throw IllegalArgumentException("unknown fixture driver $driver")
            return p["signals"]!!.jsonArray.map { s -> IdentitySignal(s.jsonArray[0].jsonPrimitive.content, s.jsonArray[1].jsonPrimitive.content) }
        }

        /** Parse a wire-schema Trip object. */
        fun tripOf(obj: JsonObject): Trip = Trip(
            parseIso(obj["start"]!!.jsonPrimitive.content), parseIso(obj["end"]!!.jsonPrimitive.content),
            obj["origin"]!!.jsonPrimitive.content, obj["destination"]!!.jsonPrimitive.content,
            obj["occupants"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            obj["settings"]?.jsonObject?.toMap() ?: emptyMap(), obj["route"]?.jsonObject?.toMap() ?: emptyMap(),
            obj["distance_km"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }
}
