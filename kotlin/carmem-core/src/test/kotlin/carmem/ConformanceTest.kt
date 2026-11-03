package carmem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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

    private fun signalsFor(fx: JsonObject, driver: String) = FixtureReplay.signalsFor(fx, driver)

    @Test
    fun `kotlin core reproduces the python reference on the household fixture`() {
        val fx = json.parseToJsonElement(File(fixturesDir, "household_seed7_w8.json").readText()).jsonObject
        val policy = Policy.fromJson(fx["policy"].toString())
        val mem = CarMemory(JdbcSqlDriver(), policy)
        val result = FixtureReplay(mem).replay(fx)
        val logged = result.proposals
        val noSaid = result.noSaid
        val forgetDone = result.forgetDone  // dest -> week
        val lastTs = result.lastTs

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
