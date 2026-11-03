package carmem

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Seeding a device from the household fixture, as the emulator demo does. */
class FixtureReplayTest {
    private val fixtureText = File(System.getProperty("carmem.fixtures") ?: "../fixtures", "household_seed7_w8.json").readText()

    @Test
    fun `seeded memory proposes the school run and climate on the next Monday`() {
        val memory = CarMemory(JdbcSqlDriver())
        var progressCalls = 0; var lastProgress = 0 to 0
        val result = FixtureReplay(memory).replay(fixtureText) { done, total -> progressCalls++; lastProgress = done to total }
        assertEquals(163, result.events)
        assertEquals(90, result.proposals.size)
        assertEquals(163, progressCalls); assertEquals(163 to 163, lastProgress)
        assertEquals(listOf("asha", "ben"), result.persons)

        // Fixture ends on a Saturday; lastTs + 3 days lands after the weekend, the demo uses the next Monday after lastTs.
        val monday = result.lastTs.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(LocalTime.of(7, 38))
        assertTrue(!monday.isBefore(result.lastTs.plusDays(1)))
        val occ = memory.ignitionOn(listOf(IdentitySignal("key_fob", "fob-asha")), monday)
        assertEquals("asha", occ.driverId)
        val props = memory.proactive(monday, "home")
        assertTrue(props.any { it.kind == "routine" && it.tier == Tier.SUGGEST && it.action["destination"]?.render() == "Oakwood Primary" }, "school-run suggestion missing: $props")
        assertTrue(props.any { it.kind == "fact" && it.tier == Tier.ACT && it.action["key"]?.render() in setOf("cabin_temp", "fan") }, "climate act proposal missing: $props")
        memory.ignitionOff(monday.plusHours(1))
        memory.close()
    }

    @Test
    fun `replaying twice into the same memory does not throw`() {
        val fx = Json.parseToJsonElement(fixtureText).jsonObject
        val memory = CarMemory(JdbcSqlDriver(), Policy.fromJson(fx["policy"].toString()))
        val replay = FixtureReplay(memory)
        val first = replay.replay(fixtureText)
        val second = replay.replay(fixtureText)
        assertEquals(first.events, second.events)
        assertEquals(listOf("asha", "ben"), second.persons)
        assertEquals("Asha", memory.store.getPerson("asha")?.name)
        memory.close()
    }

    @Test
    fun `guest driver uses the fixture guest signal`() {
        val fx = Json.parseToJsonElement(fixtureText).jsonObject
        val g = FixtureReplay.signalsFor(fx, "guest").single()
        assertEquals(fx["guest_signal"]!!.jsonObject["value"]!!.jsonPrimitive.content, g.value)
        assertEquals(0.55, g.confidence)
        assertEquals(3, FixtureReplay.signalsFor(fx, "asha").size)
    }
}
