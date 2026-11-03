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
}
