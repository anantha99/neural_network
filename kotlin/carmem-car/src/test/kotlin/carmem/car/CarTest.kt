package carmem.car

import carmem.CarMemory
import carmem.IdentitySignal
import carmem.JdbcSqlDriver
import carmem.Proposal
import carmem.State
import carmem.Tier
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarTest {
    private val home = 51.5000 to -0.1200
    private val school = 51.5120 to -0.1000   // ~1.9 km away
    private val office = 51.5300 to -0.0800

    /** Drive from a to b over `minutes` with fixes every minute, engine already on. */
    private fun drive(sink: (CarEvent) -> Unit, from: Pair<Double, Double>, to: Pair<Double, Double>, start: LocalDateTime, minutes: Int): LocalDateTime {
        for (i in 0..minutes) {
            val f = i.toDouble() / minutes
            sink(LocationFix(start.plusMinutes(i.toLong()), from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, speedMps = if (i == 0 || i == minutes) 0.0 else 9.0))
        }
        return start.plusMinutes(minutes.toLong())
    }

    @Test fun `segmenter emits a trip per leg and splits on dwell with engine running`() {
        val places = PlaceResolver()
        val trips = mutableListOf<carmem.Trip>()
        val seg = TripSegmenter(places, dwellMinutes = 5) { trips += it }
        val t0 = LocalDateTime.of(2026, 9, 7, 7, 40)
        seg.onEvent(LocationFix(t0.minusHours(10), home.first, home.second, 0.0))
        seg.onEvent(IgnitionChanged(t0, true))
        seg.onEvent(SettingChanged(t0.plusSeconds(30), "cabin_temp", JsonPrimitive(21.0)))
        var t = drive(seg::onEvent, home, school, t0, 12)
        // idle at school 7 minutes with the engine running -> dwell split
        for (i in 1..7) seg.onEvent(LocationFix(t.plusMinutes(i.toLong()), school.first, school.second, 0.0))
        t = t.plusMinutes(7)
        t = drive(seg::onEvent, school, office, t, 20)
        seg.onEvent(IgnitionChanged(t.plusMinutes(1), false))
        assertEquals(2, trips.size, trips.toString())
        assertEquals(trips[0].destination, trips[1].origin)
        assertEquals(21.0, (trips[0].settings["cabin_temp"] as JsonPrimitive).content.toDouble())
        assertTrue(trips[0].distanceKm > 1.5 && trips[1].distanceKm > 2.0)
    }

    @Test fun `place resolver clusters within radius and infers home from night dwell`() {
        val pr = PlaceResolver(radiusMeters = 150.0, homeMinNightMinutes = 60)
        val a = pr.resolve(home.first, home.second)
        val b = pr.resolve(home.first + 0.0005, home.second)      // ~55 m away: same place
        val c = pr.resolve(school.first, school.second)
        assertEquals(a.id, b.id); assertTrue(c.id != a.id)
        pr.dwell(a, LocalDateTime.of(2026, 9, 7, 22, 0), LocalDateTime.of(2026, 9, 8, 6, 0))
        assertEquals("home", a.name)
        val copy = PlaceResolver(); copy.restore(pr.snapshot())
        assertEquals(listOf("home", "place-2"), copy.all().map { it.name })
    }

    @Test fun `session resolves identity at ignition, records trips and proposes from the known place`() {
        val mem = CarMemory(JdbcSqlDriver())
        mem.enroll("asha", "Asha", listOf("key_fob" to "fob-a", "phone" to "asha-phone"))
        val proposals = mutableListOf<Proposal>()
        val session = CarSession(mem, PlaceResolver(homeMinNightMinutes = 60)) { proposals += it }
        val start = LocalDateTime.of(2026, 9, 7, 7, 40)
        // seed one night at home so the place gets its label
        session.onEvent(LocationFix(start.minusHours(9), home.first, home.second, 0.0))
        session.onEvent(IgnitionChanged(start.minusHours(9), true)); session.onEvent(IgnitionChanged(start.minusHours(9).plusMinutes(1), false))
        for (w in 0 until 4) for (d in 0 until 5) {
            val t0 = start.plusWeeks(w.toLong()).plusDays(d.toLong())
            session.onEvent(OccupantObserved(t0.minusSeconds(20), IdentitySignal("phone", "asha-phone")))
            session.onEvent(OccupantObserved(t0.minusSeconds(10), IdentitySignal("key_fob", "fob-a")))
            session.onEvent(LocationFix(t0.minusSeconds(5), home.first, home.second, 0.0))
            session.onEvent(IgnitionChanged(t0, true))
            assertEquals("asha", mem.occupancy?.driverId)
            session.onEvent(SettingChanged(t0.plusSeconds(20), "cabin_temp", JsonPrimitive(21.0)))
            val t1 = drive(session::onEvent, home, school, t0, 12)
            session.onEvent(IgnitionChanged(t1.plusSeconds(30), false))
            if (w == 0 && d == 0) session.onEvent(PlaceNamed(t1, "Oakwood Primary"))
        }
        val routines = mem.store.routines(listOf("asha"), listOf(State.CONFIRMED))
        assertTrue(routines.isNotEmpty() && routines.all { it.destination == "Oakwood Primary" && it.origin == "home" }, routines.toString())
        val routeProps = proposals.filter { it.kind == "routine" }
        assertTrue(routeProps.isNotEmpty() && routeProps.all { it.tier == Tier.SUGGEST && it.action["destination"]!!.toString().contains("Oakwood") })
        assertTrue(proposals.any { it.kind == "fact" && it.tier == Tier.ACT })
        // a voice-only stranger gets a guest session and leaves nothing behind
        val g = start.plusWeeks(5)
        session.onEvent(OccupantObserved(g.minusSeconds(5), IdentitySignal("voice", "v-stranger", 0.6)))
        session.onEvent(IgnitionChanged(g, true))
        assertTrue(mem.occupancy!!.isGuest)
        session.onEvent(UtteranceHeard(g.plusMinutes(1), "my home is 12 Elm Street"))
        session.onEvent(IgnitionChanged(g.plusMinutes(30), false))
        assertTrue(mem.store.scopesPresent().none { it.startsWith("guest:") })
    }
}
