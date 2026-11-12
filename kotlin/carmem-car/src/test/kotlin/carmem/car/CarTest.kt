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

    /** One weekday on a head unit: parked at home overnight, out in the morning, parked there all day, home for the night. */
    private fun schoolDay(session: CarSession, day: LocalDateTime, signal: IdentitySignal?) {
        if (signal != null) session.onEvent(OccupantObserved(day.minusSeconds(10), signal))
        session.onEvent(LocationFix(day.minusSeconds(5), home.first, home.second, 0.0))
        session.onEvent(IgnitionChanged(day, true))
        session.onEvent(SettingChanged(day.plusSeconds(20), "cabin_temp", JsonPrimitive(21.0)))
        val arrived = drive(session::onEvent, home, school, day, 12)
        session.onEvent(IgnitionChanged(arrived.plusSeconds(30), false))
        val back = arrived.plusHours(9)
        session.onEvent(IgnitionChanged(back, true))
        val homeAt = drive(session::onEvent, school, home, back, 12)
        session.onEvent(IgnitionChanged(homeAt.plusSeconds(30), false))
    }

    @Test fun `a new head unit learns the commute in three weeks with nobody enrolled by hand`() {
        val mem = CarMemory(JdbcSqlDriver())
        val proposals = mutableListOf<Proposal>()
        val session = CarSession(mem, PlaceResolver()) { proposals += it }       // default thresholds: home needs three nights, work three workdays
        val profile = IdentitySignal("seat_profile", "user:10")
        val monday = LocalDateTime.of(2026, 9, 7, 7, 40)
        for (w in 0 until 3) for (d in 0 until 5) schoolDay(session, monday.plusWeeks(w.toLong()).plusDays(d.toLong()), profile)

        // the Android user profile was enrolled on first sight, so memory formed from day one
        assertEquals("user:10", mem.store.lookupIdentity("seat_profile", "user:10"))
        // only the two ends of the trip are places; the 22 fixes on the road each way are not
        assertEquals(listOf("home", "work"), session.places.all().map { it.name })
        // the first days happened before the car knew which place was home and which was work, and still count
        val run = mem.store.routines(listOf("user:10"), listOf(State.CONFIRMED)).filter { it.destination == "work" }
        assertEquals(5, run.size, mem.store.routines(listOf("user:10")).toString())
        assertTrue(run.all { it.origin == "home" && it.support.size == 3 }, run.toString())
        assertTrue(mem.store.routines(listOf("user:10")).none { "place-" in it.destination || "place-" in it.origin })

        // fourth Monday: proposal from home, and nothing in the assistant's context still uses a placeholder name
        proposals.clear()
        val fourth = monday.plusWeeks(3)
        session.onEvent(OccupantObserved(fourth.minusSeconds(10), profile))
        session.onEvent(LocationFix(fourth.minusSeconds(5), home.first, home.second, 0.0))
        session.onEvent(IgnitionChanged(fourth, true))
        assertTrue(proposals.any { it.kind == "routine" && it.tier == Tier.SUGGEST && it.action["destination"].toString().contains("work") }, proposals.toString())
        assertTrue("place-" !in mem.context("where do I go on Mondays", "local", fourth).render())

        // the driver names the place while parked there: the routine follows the name
        val arrived = drive(session::onEvent, home, school, fourth, 12)
        session.onEvent(PlaceNamed(arrived, "Oakwood Primary"))
        assertTrue(mem.store.routines(listOf("user:10"), listOf(State.CONFIRMED)).any { it.destination == "Oakwood Primary" })
        assertTrue(mem.store.routines(listOf("user:10")).none { it.destination == "work" })
    }

    @Test fun `places survive a crash and unknown drivers stay guests when auto enrol is off`() {
        val mem = CarMemory(JdbcSqlDriver())
        val first = CarSession(mem, PlaceResolver(homeMinNightMinutes = 60), autoEnrollProfiles = false)
        val t0 = LocalDateTime.of(2026, 9, 7, 7, 40)
        schoolDay(first, t0, IdentitySignal("seat_profile", "user:10")); schoolDay(first, t0.plusDays(1), IdentitySignal("seat_profile", "user:10"))
        assertEquals(null, mem.store.lookupIdentity("seat_profile", "user:10"))
        assertTrue(mem.store.routines().isEmpty())                               // a guest forms no routines
        // no shutdown hook ran: a second session over the same database still knows the places and their names
        val second = CarSession(mem, PlaceResolver(homeMinNightMinutes = 60))
        assertEquals(listOf("home", "place-2"), second.places.all().map { it.name })
        assertEquals("home", second.places.find(home.first, home.second)?.name)
    }

    @Test fun `on the road the car is moving and at no known place`() {
        val mem = CarMemory(JdbcSqlDriver())
        val session = CarSession(mem, PlaceResolver())
        val t0 = LocalDateTime.of(2026, 9, 7, 7, 40)
        assertEquals(null, session.location)
        session.onEvent(LocationFix(t0.minusSeconds(5), home.first, home.second, 0.0))
        session.onEvent(IgnitionChanged(t0, true))
        assertEquals(false, session.moving); assertEquals("place-1", session.location)
        session.onEvent(LocationFix(t0.plusMinutes(5), (home.first + school.first) / 2, (home.second + school.second) / 2, 9.0))
        assertEquals(true, session.moving); assertEquals(CarSession.UNKNOWN_PLACE, session.location)
        session.onEvent(IgnitionChanged(t0.plusMinutes(6), false))
        assertEquals(false, session.moving)
    }
}
