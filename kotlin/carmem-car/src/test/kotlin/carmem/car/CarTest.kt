package carmem.car

import carmem.CarMemory
import carmem.IdentitySignal
import carmem.JdbcSqlDriver
import carmem.LlmClient
import carmem.Occupancy
import carmem.Person
import carmem.Proposal
import carmem.State
import carmem.Tier
import carmem.Trip
import carmem.routineTombstoneKey
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ------------------------------------------------------------ stable place identity

    @Test fun `routines follow a place that is labelled after the first week`() {
        val mem = CarMemory(JdbcSqlDriver())
        mem.enroll("asha", "Asha", listOf("key_fob" to "fob-a"))
        val renames = mutableListOf<Pair<String, String>>()
        // home needs six nights: Mon-Thu nights are not enough, the first weekend is
        val places = PlaceResolver(homeMinNightMinutes = 6 * 60 * 6) { _, old, new -> renames += old to new }
        val session = CarSession(mem, places)
        val fob = IdentitySignal("key_fob", "fob-a")
        val start = LocalDateTime.of(2026, 9, 7, 7, 40) // a Monday
        for (w in 0 until 4) for (d in 0 until 5) {
            val t0 = start.plusWeeks(w.toLong()).plusDays(d.toLong())
            session.onEvent(OccupantObserved(t0.minusSeconds(10), fob))
            session.onEvent(LocationFix(t0.minusSeconds(5), home.first, home.second, 0.0))
            session.onEvent(IgnitionChanged(t0, true))
            val t1 = drive(session::onEvent, home, school, t0, 12)
            session.onEvent(IgnitionChanged(t1.plusSeconds(30), false))
            if (w == 1 && d == 0) session.onEvent(PlaceNamed(t1.plusMinutes(1), "Oakwood Primary"))
            val t2 = t0.withHour(15).withMinute(30)
            session.onEvent(OccupantObserved(t2.minusSeconds(10), fob))
            session.onEvent(IgnitionChanged(t2, true))
            val t3 = drive(session::onEvent, school, home, t2, 12)
            session.onEvent(IgnitionChanged(t3.plusSeconds(30), false))
            if (w == 0) assertTrue(places.all().none { it.label == "home" }, "home must not be known in week one")
        }
        assertTrue(("place-1" to "home") in renames, renames.toString())
        assertEquals("Oakwood Primary", renames.last { it.first != "place-1" }.second)
        val week2 = start.plusWeeks(1).toLocalDate()
        val routines = mem.store.routines(listOf("asha"))
        assertEquals(10, routines.size, routines.joinToString("\n") { it.describe() })
        assertTrue(routines.all { it.state == State.CONFIRMED }, routines.joinToString("\n") { "${it.describe()} ${it.state} ${it.stats}" })
        val morning = routines.filter { it.minuteOfDay < 12 * 60 }
        val evening = routines.filter { it.minuteOfDay >= 12 * 60 }
        assertTrue(morning.size == 5 && morning.all { it.origin == "home" && it.destination == "Oakwood Primary" && it.action["destination"] == JsonPrimitive("Oakwood Primary") })
        assertTrue(evening.size == 5 && evening.all { it.origin == "Oakwood Primary" && it.destination == "home" })
        for (r in routines) {
            val trips = r.support.filter { it.note == "trip" }
            assertEquals(4, trips.size, r.toString())
            assertTrue(trips.any { carmem.parseIso(it.ts).toLocalDate().isBefore(week2) }, "early trips count toward ${r.describe()}")
            assertTrue(trips.all { (it.value as JsonPrimitive).content == r.destination && it.condition == r.origin }, r.toString())
        }
        val eps = mem.store.episodes(listOf("asha"), listOf("trip"))
        assertTrue(eps.size == 40 && eps.none { (ep, _) -> "place-" in ep.text || "work" in ep.text }, eps.map { it.first.text }.toString())
    }

    @Test fun `renamePlace merges duplicate routines and moves tombstones and episodes`() {
        val mem = CarMemory(JdbcSqlDriver())
        mem.enroll("asha", "Asha", listOf("key_fob" to "fob-a"))
        val mon = LocalDateTime.of(2026, 9, 7, 17, 0)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), mon.minusMinutes(1))
        for (w in 0 until 4) {
            val t = mon.plusWeeks(w.toLong())
            mem.recordTrip(Trip(t, t.plusMinutes(15), "Oakwood Primary", if (w < 2) "place-6" else "home", distanceKm = 2.0))
        }
        mem.store.addTombstone("asha", routineTombstoneKey("place-9"), mon.plusYears(1), "driver asked")
        val before = mem.store.routines(listOf("asha"))
        assertEquals(2, before.size)
        assertTrue(before.none { it.state == State.CONFIRMED })
        val now = mon.plusWeeks(3).plusHours(1)
        val touched = mem.renamePlace(null, "place-6", "home", now)
        val after = mem.store.routines(listOf("asha"))
        assertEquals(1, after.size, after.toString())
        val r = after.single()
        assertEquals("0:home", r.signature); assertEquals("home", r.destination)
        assertEquals(State.CONFIRMED, r.state, "${r.stats} ${r.contradictions}")
        assertEquals(4, r.support.count { it.note == "trip" })
        assertTrue(r.contradictions.isEmpty(), r.contradictions.toString())
        assertEquals(3, touched) // the place-6 routine (merged away) and its two trip episodes
        assertTrue(mem.store.episodes(listOf("asha"), listOf("trip")).none { "place-6" in it.first.text || "place-6" in it.first.payload.toString() })
        mem.renamePlace("asha", "place-9", "gym", now)
        assertTrue(mem.store.isTombstoned("asha", routineTombstoneKey("gym"), now))
        assertFalse(mem.store.isTombstoned("asha", routineTombstoneKey("place-9"), now))
        assertTrue(mem.store.auditLog().any { it["event"] == "place.renamed" })
    }

    // ------------------------------------------------------------ auto-enrollment

    @Test fun `unknown seat profile enrolls, phone accretes to the driver, voice alone stays a guest`() {
        val mem = CarMemory(JdbcSqlDriver())
        val seen = mutableListOf<Pair<Occupancy, Person?>>()
        val session = CarSession(mem, onDriver = { o, p -> seen += o to p })
        val profile = IdentitySignal("seat_profile", "user:10")
        val phone = IdentitySignal("phone", "AA:BB:CC")
        var t = LocalDateTime.of(2026, 9, 7, 7, 40)
        fun ignition(vararg signals: IdentitySignal): Occupancy {
            signals.forEach { session.onEvent(OccupantObserved(t.minusSeconds(10), it)) }
            session.onEvent(IgnitionChanged(t, true))
            return mem.occupancy!!
        }
        fun off() { session.onEvent(IgnitionChanged(t.plusMinutes(20), false)); t = t.plusDays(1) }

        // a phone alone on an empty car never enrolls anyone
        assertTrue(ignition(IdentitySignal("phone", "stranger")).isGuest); off()
        assertNull(mem.store.lookupIdentity("phone", "stranger"))

        val first = ignition(profile, phone)
        assertFalse(first.isGuest)
        assertEquals("seat_profile:user:10", first.driverId)
        assertEquals("Driver user:10", seen.last().second?.name)
        off()
        val second = ignition(profile, phone)
        assertEquals("seat_profile:user:10", second.driverId); assertFalse(second.isGuest)
        assertEquals("seat_profile:user:10", mem.store.lookupIdentity("phone", "AA:BB:CC"))
        off()
        assertEquals("seat_profile:user:10", ignition(phone).driverId) // the phone now identifies her on its own
        session.onEvent(UtteranceHeard(t.plusMinutes(1), "my name is Asha"))
        assertEquals("Asha", mem.store.getPerson("seat_profile:user:10")?.name)
        assertTrue(mem.store.facts(listOf("seat_profile:user:10")).none { it.category == "identity" })
        off()

        val guest = ignition(IdentitySignal("voice", "v-1", 0.9))
        assertTrue(guest.isGuest); assertNull(seen.last().second)
        session.onEvent(UtteranceHeard(t.plusMinutes(1), "my name is Bob"))
        off()
        assertNull(mem.store.lookupIdentity("voice", "v-1"))
        assertEquals("Asha", mem.store.getPerson("seat_profile:user:10")?.name)
    }

    @Test fun `a phone seen with two different drivers is not bound to either`() {
        val mem = CarMemory(JdbcSqlDriver())
        mem.enroll("asha", "Asha", listOf("key_fob" to "fob-a")); mem.enroll("ben", "Ben", listOf("seat_profile" to "user:11"))
        val session = CarSession(mem)
        val t = LocalDateTime.of(2026, 9, 7, 7, 40)
        listOf(IdentitySignal("seat_profile", "user:11"), IdentitySignal("key_fob", "fob-a"), IdentitySignal("phone", "p"))
            .forEach { session.onEvent(OccupantObserved(t.minusSeconds(5), it)) }
        session.onEvent(IgnitionChanged(t, true))
        assertNull(mem.store.lookupIdentity("phone", "p"))
    }

    // ------------------------------------------------------------ demo clock

    private fun seedMondaySchoolRun(mem: CarMemory, origin: String = "home"): LocalDateTime {
        val mon = LocalDateTime.of(2026, 9, 7, 7, 40)
        for (w in 0 until 4) {
            val t = mon.plusWeeks(w.toLong())
            mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t.minusMinutes(1))
            mem.recordTrip(Trip(t, t.plusMinutes(12), origin, "Oakwood Primary", distanceKm = 2.0))
            mem.ignitionOff(t.plusMinutes(13))
        }
        return mon.plusWeeks(4)
    }

    @Test fun `demo clock drives proactive without explicit times`() {
        val clock = DemoClock()
        val mem = CarMemory(JdbcSqlDriver(), clock = clock)
        mem.enroll("asha", "Asha", listOf("key_fob" to "fob-a"))
        val nextMonday = seedMondaySchoolRun(mem)
        clock.set(nextMonday.withHour(7).withMinute(38))
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")))
        val props = mem.proactive()
        assertTrue(props.any { it.kind == "routine" && it.action["destination"] == JsonPrimitive("Oakwood Primary") }, props.toString())
        clock.advance(Duration.ofHours(2))
        assertTrue(mem.proactive().none { it.kind == "routine" })
        assertTrue(Duration.between(nextMonday.withHour(9).withMinute(38), clock()).abs() < Duration.ofSeconds(5))
        clock.reset()
        assertFalse(clock.isPinned)
        assertTrue(Duration.between(LocalDateTime.now(), clock()).abs() < Duration.ofSeconds(5))
    }

    // ------------------------------------------------------------ background extraction

    private class SlowLlm : LlmClient {
        override fun completeJson(system: String, user: String, schemaJson: String): String {
            Thread.sleep(300)
            return if ("22" in user) """{"candidates":[{"category":"climate","key":"cabin_temp","value":"22","condition":null,"kind":"stated","about":"person"}]}"""
            else """{"candidates":[]}"""
        }
    }

    @Test fun `async extraction does not block hear and intents are applied immediately`() {
        val mem = CarMemory(JdbcSqlDriver(), llm = SlowLlm())
        mem.enroll("asha", "Asha", listOf("key_fob" to "fob-a"))
        val monday = seedMondaySchoolRun(mem)
        val mailbox = LinkedBlockingQueue<() -> Unit>()   // stands in for the service's worker Handler
        val session = CarSession(mem, asyncExtraction = true, runOnMemoryThread = { mailbox.put(it) })
        val t = monday.withHour(7).withMinute(38)
        session.onEvent(OccupantObserved(t.minusSeconds(10), IdentitySignal("key_fob", "fob-a")))
        session.onEvent(IgnitionChanged(t, true))
        val route = mem.proactive(t).single { it.kind == "routine" }
        session.onEvent(UtteranceHeard(t.plusSeconds(5), "good morning"))  // warm up; also queues a job
        val t0 = System.nanoTime()
        session.onEvent(UtteranceHeard(t.plusSeconds(10), "I like it at 22 degrees"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 50, "hear took $ms ms")
        assertTrue(mem.store.facts(listOf("asha"), "climate", "cabin_temp").none { it.source == "stated" })
        session.onEvent(UtteranceHeard(t.plusSeconds(15), "no"))
        assertTrue(session.extraction!!.pending > 0, "extraction should still be pending")
        assertTrue(mem.store.getRoutine(route.recordId)!!.contradictions.any { it.note == "said no" }, "the no is applied at once")
        assertTrue(session.extraction!!.drain(Duration.ofSeconds(10)))
        while (true) (mailbox.poll() ?: break).invoke()
        val fact = mem.store.facts(listOf("asha"), "climate", "cabin_temp").single { it.source == "stated" }
        assertEquals(22.0, (fact.value as JsonPrimitive).content.toDouble())
        session.close()
    }
}
