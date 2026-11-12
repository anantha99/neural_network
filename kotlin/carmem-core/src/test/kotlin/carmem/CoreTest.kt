package carmem

import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    private fun make(): CarMemory = CarMemory(JdbcSqlDriver()).also { it.enroll("asha", "Asha", listOf("key_fob" to "fob-a")) }
    private fun schoolRun(mem: CarMemory, weeks: Int, start: LocalDateTime = LocalDateTime.of(2026, 9, 7, 7, 40)): LocalDateTime {
        var last = start
        for (w in 0 until weeks) for (d in 0 until 5) {
            val t = start.plusWeeks(w.toLong()).plusDays(d.toLong())
            mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
            mem.recordTrip(Trip(t, t.plusMinutes(15), "home", "Oakwood Primary", listOf("asha"), mapOf("cabin_temp" to JsonPrimitive(21.0))))
            mem.ignitionOff(t.plusMinutes(30)); last = t
        }
        return last
    }

    @Test fun `voice alone is a guest and is wiped`() {
        val mem = make(); val t = LocalDateTime.of(2026, 9, 7, 8, 0)
        val occ = mem.ignitionOn(listOf(IdentitySignal("voice", "v-x", 0.95)), t)
        assertTrue(occ.isGuest)
        mem.hear("my home is 12 Elm Street", now = t)
        assertEquals(1, mem.store.facts(listOf(occ.driverId)).size)
        mem.ignitionOff(t)
        assertEquals(0, mem.store.facts(listOf(occ.driverId)).size)
    }

    @Test fun `routine confirms after three weeks and proposes in slot with tiers`() {
        val mem = make(); val last = schoolRun(mem, 4); val nxt = last.plusDays(3)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), nxt)
        val props = mem.proactive(nxt, "home")
        assertTrue(props.any { it.kind == "routine" && it.tier == Tier.SUGGEST })
        assertTrue(props.any { it.kind == "fact" && it.tier == Tier.ACT })
        val r = mem.hear("no", now = nxt)
        assertTrue(r["correction"].toString().contains("\"applied\":true"))
        assertTrue(mem.proactive(nxt, "home").none { it.kind == "routine" })
    }

    @Test fun `forget tombstones and blocks relearning`() {
        val mem = make(); val last = schoolRun(mem, 4); val nxt = last.plusDays(3)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), nxt); mem.proactive(nxt, "home")
        val res = mem.hear("forget that", now = nxt)
        assertTrue(res["forget"].toString().contains("routine"))
        assertTrue(mem.store.routines(listOf("asha")).isEmpty())
        mem.ignitionOff(nxt.plusHours(1))
        schoolRun(mem, 4, nxt.plusDays(7))
        assertTrue(mem.store.routines(listOf("asha")).isEmpty())
    }

    @Test fun `superseded fact keeps validity interval and notes reconcile`() {
        val mem = make(); val t = LocalDateTime.of(2026, 9, 7, 8, 0)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
        mem.hear("my kid's school is Oakwood Primary", now = t)
        mem.hear("my kid's school is Hillcrest Academy", now = t.plusDays(30))
        val hist = mem.factHistory("school", "school_location")
        assertEquals(listOf("Oakwood Primary", "Hillcrest Academy"), hist.map { it.value.render() })
        assertEquals(iso(t.plusDays(30)), hist[0].validUntil)
        val a = mem.hear("remember that the parking code at the office garage is 4471", now = t)["stored"].toString()
        val b = mem.hear("remember the parking code at the office garage is 4471", now = t)["stored"].toString()
        assertEquals(a.substringAfter("\"id\":\"").substringBefore("\""), b.substringAfter("\"id\":\"").substringBefore("\""))
        mem.hear("remember that the parking code at the office garage is now 9920", now = t.plusDays(1))
        assertEquals(1, mem.store.facts(listOf("asha"), "note", states = listOf(State.CONFIRMED)).size)
    }

    @Test fun `cloud packet masks and unmasks`() {
        val mem = make(); val t = LocalDateTime.of(2026, 9, 7, 8, 0)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
        mem.hear("my kid's school is Oakwood Primary", now = t); mem.hear("I prefer 21 degrees", now = t)
        val pkt = mem.context("school", "cloud", t)
        val text = pkt.render()
        assertTrue("Oakwood" !in text && "21" in text)
        val token = pkt.masked.entries.first { it.value == "Oakwood Primary" }.key
        assertEquals("Go to Oakwood Primary", pkt.unmask("Go to $token"))
    }

    @Test fun `only settings become set proposals and reconcile sees the subject`() {
        val prompts = mutableListOf<String>()
        fun cand(cat: String, key: String, value: String) =
            """{"candidates":[{"category":"$cat","key":"$key","value":"$value","condition":null,"kind":"stated","about":"person"}]}"""
        val llm = object : LlmClient {
            override fun completeJson(system: String, user: String, schemaJson: String): String { prompts += user; return when {
                user.startsWith("NEW:") -> """{"op":"update","target_index":0}"""
                "country" in user -> cand("media", "disliked_genre", "country")
                "Tuesdays" in user -> cand("note", "leo_piano_lesson", "Tuesdays at 5")
                "Wednesdays" in user -> cand("note", "leo_piano_lesson_schedule", "Wednesdays at 5")
                else -> cand("climate", "cabin_temp", "22")
            } }
        }
        val mem = CarMemory(JdbcSqlDriver(), llm = llm).also { it.enroll("asha", "Asha", listOf("key_fob" to "fob-a")) }
        val t = LocalDateTime.of(2026, 11, 2, 7, 30)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
        for (u in listOf("I like it at 22", "I can't stand country music", "Leo's piano is Tuesdays at 5", "Leo's piano moved to Wednesdays at 5")) mem.hear(u, now = t)
        val props = mem.proactive(t, "home")
        assertTrue(props.any { it.action["key"] == JsonPrimitive("cabin_temp") }, "$props")
        assertTrue(props.none { it.action["key"] == JsonPrimitive("disliked_genre") }, "$props")
        val reconcile = prompts.last { it.startsWith("NEW:") }
        assertTrue("leo piano lesson schedule: Wednesdays at 5" in reconcile && "leo piano lesson: Tuesdays at 5" in reconcile, reconcile)
        val notes = mem.store.facts(listOf("asha"), "note").filter { it.isCurrent }
        assertEquals(listOf("Wednesdays at 5"), notes.map { it.value.render() })
    }

    @Test fun `evidence stays bounded however long the car is driven`() {
        val mem = make(); schoolRun(mem, 40)
        val temp = mem.store.facts(listOf("asha"), "climate", "cabin_temp").first { it.source == "implicit" && it.condition == null }
        // one observation per trip for 200 trips; only the 56-day window (plus a day of slack) is kept
        assertTrue(temp.support.size <= 5 * 9 + 1, "implicit fact kept ${temp.support.size} observations")
        assertTrue(mem.store.routines(listOf("asha")).all { it.support.size <= 10 }, mem.store.routines(listOf("asha")).map { it.support.size }.toString())
        // and the state machine still sees what it needs: confirmed, and explain() has its trail
        assertEquals(State.CONFIRMED, temp.state)
        val rt = mem.store.routines(listOf("asha"), listOf(State.CONFIRMED)).first()
        assertTrue(mem.explain(rt.id)["because"].toString().contains("Oakwood Primary"))
    }

    @Test fun `a write that fails half way leaves nothing behind`() {
        val mem = make(); val t = LocalDateTime.of(2026, 9, 7, 8, 0)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
        val before = listOf("episodes", "facts", "routines", "audit").associateWith { mem.store.count(it) }
        val failed = runCatching { mem.store.tx { mem.recordTrip(Trip(t, t.plusMinutes(15), "home", "Oakwood Primary", listOf("asha"), mapOf("cabin_temp" to JsonPrimitive(21.0)))); error("power cut") } }
        assertTrue(failed.isFailure)
        assertEquals(before, listOf("episodes", "facts", "routines", "audit").associateWith { mem.store.count(it) })
        mem.recordTrip(Trip(t, t.plusMinutes(15), "home", "Oakwood Primary", listOf("asha")))   // and the store still works
        assertEquals(before["episodes"]!! + 1, mem.store.count("episodes"))
    }

    @Test fun `context reflects a change made since the last call`() {
        val mem = make(); val t = LocalDateTime.of(2026, 9, 7, 8, 0)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
        mem.hear("my kid's school is Oakwood Primary", now = t)
        assertTrue("Oakwood Primary" in mem.context("school", "local", t).render())
        mem.hear("my kid's school is Hillcrest Academy", now = t.plusMinutes(1))
        val after = mem.context("school", "local", t.plusMinutes(2)).render()
        assertTrue("school/school_location = Hillcrest Academy" in after && "school/school_location = Oakwood Primary" !in after, after)
        mem.hear("forget that", now = t.plusMinutes(3))
        assertTrue("school/school_location" !in mem.context("", "local", t.plusMinutes(4)).render())
        // another driver never sees the first driver's cached context
        mem.enroll("ben", "Ben", listOf("key_fob" to "fob-b")); mem.hear("I'm allergic to peanuts", now = t.plusMinutes(5))
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-b")), t.plusHours(1))
        assertTrue("peanuts" !in mem.context("allergy", "local", t.plusHours(1)).render())
    }

    @Test fun `episodes written before vectors became blobs still rank`() {
        val db = JdbcSqlDriver(); val mem = CarMemory(db); mem.enroll("asha", "Asha", listOf("key_fob" to "fob-a"))
        val t = LocalDateTime.of(2026, 9, 7, 8, 0)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
        val text = "the dentist moved my appointment to Thursday"
        db.execute("INSERT INTO episodes(scope,kind,ts,text,payload,embedding) VALUES (?,?,?,?,?,?)",
            listOf("asha", "utterance", iso(t), text, "{}", mem.embedder.embed(text).joinToString(",", "[", "]")))
        mem.hear("play some jazz", now = t.plusMinutes(1))
        assertEquals(text, mem.context("dentist appointment", "local", t.plusMinutes(2)).episodes.first().text)
    }

    @Test fun `a database from a newer build is refused`() {
        val db = JdbcSqlDriver(); CarMemory(db)
        db.execute("UPDATE meta SET value=? WHERE key='schema_version'", listOf((SCHEMA_VERSION + 1).toString()))
        assertTrue(runCatching { Store(db) }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `seat is not moved while the car is moving, climate still is`() {
        val mem = make(); var t = LocalDateTime.of(2026, 9, 7, 7, 40)
        repeat(4) {
            mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
            mem.recordTrip(Trip(t, t.plusMinutes(15), "home", "work", listOf("asha"), mapOf("cabin_temp" to JsonPrimitive(21.0), "seat_position" to JsonPrimitive("profile-2"))))
            mem.ignitionOff(t.plusHours(1)); t = t.plusDays(1)
        }
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
        fun keys(moving: Boolean) = mem.proactive(t, "home", moving).filter { it.kind == "fact" }.map { it.action["key"]!!.render() }.toSet()
        assertEquals(setOf("cabin_temp", "seat_position"), keys(moving = false))
        assertEquals(setOf("cabin_temp"), keys(moving = true))
        // "yes" to a setting counts for it, however often it is said
        repeat(3) { mem.proactive(t, "home"); mem.hear("yes", now = t.plusMinutes(1L + it)) }
        mem.recordTrip(Trip(t, t.plusMinutes(15), "home", "work", listOf("asha"), mapOf("cabin_temp" to JsonPrimitive(21.0), "seat_position" to JsonPrimitive("profile-2"))))
        assertEquals(setOf("cabin_temp", "seat_position"), mem.store.facts(listOf("asha"), states = listOf(State.CONFIRMED)).map { it.key }.toSet())
    }

    @Test fun `the remember tool keeps what the assistant sends even when no pattern matches`() {
        val mem = make(); val t = LocalDateTime.of(2026, 9, 7, 8, 0)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
        fun remember(text: String) = mem.handleTool("memory_remember", kotlinx.serialization.json.buildJsonObject { put("text", JsonPrimitive(text)) })
        assertTrue(remember("the side gate sticks unless you lift it")["stored"].toString().contains("note/"))
        assertTrue(remember("my daughter Mia needs her booster seat")["stored"].toString().contains("children/"))
        assertTrue(remember("I prefer 22 degrees")["stored"].toString().contains("climate/cabin_temp"))   // known patterns stay typed
        assertEquals("[]", remember("no")["stored"].toString())                                              // a correction is not a note
        val local = mem.context("side gate", "local", t.plusMinutes(10)).render(); val cloud = mem.context("booster seat side gate", "cloud", t.plusMinutes(10)).render()
        assertTrue("note/the_side_gate_sticks_unless_you_lift_it = the side gate sticks unless you lift it" in local, local)
        // private by default: the cloud gets neither a note's text nor the key made from it, in any letter case
        assertTrue("mia" !in cloud.lowercase() && "gate" !in cloud.lowercase() && "note/(masked) = <note_" in cloud, cloud)
    }

    @Test fun `renaming a place carries its routine, evidence and tombstone to the new name`() {
        val mem = make(); val start = LocalDateTime.of(2026, 9, 7, 7, 40)
        fun trip(t: LocalDateTime, to: String) { mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t); mem.recordTrip(Trip(t, t.plusMinutes(15), "home", to, listOf("asha"))); mem.ignitionOff(t.plusHours(1)) }
        trip(start, "place-4"); trip(start.plusWeeks(1), "place-4")          // before the place had a name
        trip(start.plusWeeks(2), "Oakwood Primary")                           // after: same habit, new name
        val at = start.plusWeeks(2).plusHours(2)
        assertEquals(2, mem.store.routines(listOf("asha")).size)
        val res = mem.renamePlace("place-4", "Oakwood Primary", at)
        assertEquals("1", res["routines"].toString())
        val rt = mem.store.routines(listOf("asha")).single()
        assertEquals("Oakwood Primary", rt.destination); assertEquals(State.CONFIRMED, rt.state)   // three weeks, counted together
        assertEquals(3, rt.support.size)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), at)
        val ctx = mem.context("trip home to Oakwood Primary", "local", at).render()
        assertTrue("place-4" !in ctx && "trip home -> Oakwood Primary" in ctx, ctx)
        // a forgotten place stays forgotten under its new name
        trip(start.plusDays(1), "place-9"); mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), at)
        mem.forget(mem.store.routines(listOf("asha")).first { it.destination == "place-9" }.id, at)
        mem.renamePlace("place-9", "the gym", at)
        mem.recordTrip(Trip(at.plusDays(1), at.plusDays(1).plusMinutes(15), "home", "the gym", listOf("asha")))
        assertTrue(mem.store.routines(listOf("asha")).none { it.destination == "the gym" })
    }

    @Test fun `latency recorder reports percentiles, queue time and calls over budget`() {
        var clock = 0L
        val rec = LatencyRecorder(window = 100, nanoTime = { clock })
        rec.budgetMs = 50.0
        for (i in 1..100) { val asked = clock; clock += 1_000_000; val started = clock; clock += i * 1_000_000L; rec.record("context", asked, started) }
        val c = rec.toJson()["calls"].toString()
        assertTrue("\"count\":100" in c && "\"over_budget\":51" in c && "\"max_ms\":101.0" in c && "\"queued_p99_ms\":1.0" in c, c)
        assertTrue("\"p50_ms\":52.0" in c, c)
        assertEquals(7, rec.time("x") { 7 })
    }
}
