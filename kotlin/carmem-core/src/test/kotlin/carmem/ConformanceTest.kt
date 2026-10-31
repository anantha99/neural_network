package carmem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays the fixture exported by the Python reference (bench/export_fixtures.py)
 * with the protocol in fixtures/wire-schema.json and checks the same outcomes.
 */
class ConformanceTest {
    private val fixturesDir = File(System.getProperty("carmem.fixtures") ?: "../fixtures")
    private val json = Json { ignoreUnknownKeys = true }

    private data class Logged(val ts: String, val scope: String, val dest: String, val tier: String, val correct: Boolean, val week: Int)

    private fun signalsFor(fx: JsonObject, driver: String): List<IdentitySignal> {
        if (driver == "guest") { val g = fx["guest_signal"]!!.jsonObject; return listOf(IdentitySignal(g["kind"]!!.jsonPrimitive.content, g["value"]!!.jsonPrimitive.content, g["confidence"]!!.jsonPrimitive.double)) }
        val p = fx["persons"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == driver }
        return p["signals"]!!.jsonArray.map { s -> IdentitySignal(s.jsonArray[0].jsonPrimitive.content, s.jsonArray[1].jsonPrimitive.content) }
    }

    private fun tripOf(o: JsonObject) = Trip(
        parseIso(o["start"]!!.jsonPrimitive.content), parseIso(o["end"]!!.jsonPrimitive.content),
        o["origin"]!!.jsonPrimitive.content, o["destination"]!!.jsonPrimitive.content,
        o["occupants"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        o["settings"]?.jsonObject?.toMap() ?: emptyMap(), o["route"]?.jsonObject?.toMap() ?: emptyMap(),
        o["distance_km"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    )

    @Test
    fun `kotlin core reproduces the python reference on the household fixture`() {
        val fx = json.parseToJsonElement(File(fixturesDir, "household_seed7_w8.json").readText()).jsonObject
        val policy = Policy.fromJson(fx["policy"].toString())
        val mem = CarMemory(JdbcSqlDriver(), policy)
        for (p in fx["persons"]!!.jsonArray.map { it.jsonObject })
            mem.enroll(p["id"]!!.jsonPrimitive.content, p["name"]!!.jsonPrimitive.content, p["signals"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content })

        val events = fx["events"]!!.jsonArray.map { it.jsonObject }
        val logged = mutableListOf<Logged>()
        val noSaid = mutableMapOf<String, Int>()
        val forgetDone = mutableMapOf<String, Int>()  // dest -> week
        var current: Pair<String, java.time.LocalDate>? = null
        var lastTs: LocalDateTime = parseIso(events.first()["ts"]!!.jsonPrimitive.content)

        for (ev in events) {
            val ts = parseIso(ev["ts"]!!.jsonPrimitive.content); lastTs = ts
            val type = ev["type"]!!.jsonPrimitive.content; val driver = ev["driver"]!!.jsonPrimitive.content
            val week = ev["week"]!!.jsonPrimitive.int
            if (type == "sensor") { val pl = ev["payload"]!!.jsonObject; mem.recordVehicleEvent(pl["key"]!!.jsonPrimitive.content, pl["value"]!!, now = ts); continue }
            val key = driver to ts.toLocalDate()
            if (key != current) {
                if (current != null) mem.ignitionOff(ts.minusMinutes(1))
                mem.ignitionOn(signalsFor(fx, driver), ts.minusMinutes(5)); current = key
            }
            if (type == "say") { mem.hear(ev["text"]!!.jsonPrimitive.content, now = ts); continue }
            val trip = tripOf(ev["trip"]!!.jsonObject)
            val reject = ev["reject_if_proposed"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val forget = ev["forget_if_proposed"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            for (p in mem.proactive(trip.start.minusMinutes(2), trip.origin)) {
                if (p.kind != "routine") continue
                val dest = p.action["destination"]!!.render()
                logged += Logged(iso(trip.start), p.scope, dest, p.tier.wire, normPlace(dest) == normPlace(trip.destination), week)
                if (reject.any { normPlace(it) == normPlace(dest) }) { mem.hear("no", now = trip.start.minusMinutes(1)); noSaid[dest] = (noSaid[dest] ?: 0) + 1 }
                if (forget.any { normPlace(it) in normPlace(dest) }) { mem.hear("forget that", now = trip.start.minusMinutes(1)); forgetDone[dest] = week }
            }
            if (forget.isNotEmpty() && forgetDone.keys.none { k -> forget.any { normPlace(it) in normPlace(k) } }) {
                mem.hear("forget that I go to the ${forget[0]}", now = trip.start.minusMinutes(1)); forgetDone[forget[0]] = week
            }
            mem.recordTrip(trip, trip.end)
        }
        mem.ignitionOff(lastTs.plusHours(1))

        val expected = fx["expected"]!!.jsonObject

        // --- confirmed routines per scope
        val confirmed = mem.store.routines(null, listOf(State.CONFIRMED)).groupBy({ it.scope }, { it.destination }).mapValues { it.value.toSet() }
        val expRoutines = expected["confirmed_routines"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content }.toSet() }
        assertEquals(expRoutines, confirmed, "confirmed routines")

        // --- exact proposal sequence
        val expProps = expected["proposals"]!!.jsonArray.map { it.jsonObject }.map { Triple(it["ts"]!!.jsonPrimitive.content.take(19), it["scope"]!!.jsonPrimitive.content, it["dest"]!!.jsonPrimitive.content) to (it["tier"]!!.jsonPrimitive.content to it["correct"]!!.jsonPrimitive.boolean) }
        val gotProps = logged.map { Triple(it.ts, it.scope, it.dest) to (it.tier to it.correct) }
        assertEquals(expProps.size, gotProps.size, "proposal count")
        assertEquals(expProps, gotProps, "proposal sequence")

        // --- corrections and forget
        assertEquals(expected["no_said"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }, noSaid, "no said")
        val expForget = expected["forget"]!!.jsonObject
        val forgotDest = expForget["target"]!!.jsonPrimitive.content
        assertEquals(expForget["forgotten_in_week"]!!.jsonPrimitive.int, forgetDone.entries.first { normPlace(forgotDest) in normPlace(it.key) || normPlace(it.key) in normPlace(forgotDest) }.value, "forget week")
        val fw = expForget["forgotten_in_week"]!!.jsonPrimitive.int
        assertEquals(0, logged.count { it.dest == forgotDest && it.week > fw }, "proposals after forget")
        assertTrue(mem.store.routines(listOf("asha")).none { it.destination == forgotDest }, "forgotten routine still exists")
        assertEquals(expected["tombstones"]!!.jsonArray.map { it.jsonObject["scope"]!!.jsonPrimitive.content to it.jsonObject["signature"]!!.jsonPrimitive.content }.toSet(),
            mem.store.tombstones().map { it["scope"] as String to it["signature"] as String }.toSet(), "tombstones")

        // --- confirmed facts (normalised text)
        val gotFacts = mem.store.facts(null, states = listOf(State.CONFIRMED)).groupBy({ it.scope }, { it.describe().lowercase() }).mapValues { it.value.toSet() }
        val expFacts = expected["confirmed_facts"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content.lowercase() }.toSet() }
        assertEquals(expFacts, gotFacts, "confirmed facts")

        // --- leakage and guest residue
        assertTrue(mem.store.scopesPresent().none(::isGuest), "guest scope remained")
        val all = mem.store.facts(null).joinToString(" ") { it.scope + " " + it.describe() } + mem.store.episodes(mem.store.scopesPresent().toList(), limit = 100000).joinToString(" ") { it.first.scope + " " + it.first.text }
        assertTrue("Elm" !in all, "guest string leaked")

        // --- QA on rendered context
        val day = lastTs.plusDays(1)
        for (q in fx["qa"]!!.jsonArray.map { it.jsonObject }) {
            val who = q["who"]!!.jsonPrimitive.content
            mem.ignitionOn(signalsFor(fx, who), day)
            val text = mem.context(q["q"]!!.jsonPrimitive.content, "local", day).render().lowercase()
            val expect = q["expect"]!!.jsonArray.map { it.jsonPrimitive.content.lowercase() }; val forbid = q["forbid"]!!.jsonArray.map { it.jsonPrimitive.content.lowercase() }
            assertTrue(expect.all { it in text } && forbid.none { it in text }, "QA failed for $who: ${q["q"]}\n$text")
            mem.ignitionOff(day.plusHours(1))
        }

        // --- cloud masking
        mem.ignitionOn(signalsFor(fx, "asha"), day)
        val cloud = mem.context("school", "cloud", day)
        assertEquals(expected["cloud_masked_fields"]!!.jsonPrimitive.int, cloud.masked.size, "masked fields")
        val rendered = cloud.render()
        assertTrue("Oakwood" !in rendered && "penicillin" !in rendered, "sensitive value leaked to cloud packet")
        mem.ignitionOff(day.plusDays(1))
        mem.close()
    }
}
