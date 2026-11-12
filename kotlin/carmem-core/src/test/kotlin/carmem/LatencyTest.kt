package carmem

import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDateTime
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Latency of every call an assistant makes, against a file-backed database holding six months of a
 * two-driver household: about 1,500 trips, 2,500 utterances and 80 stated facts.
 *
 * Run with `gradle :carmem-core:latency`; it is tagged out of `gradle test` because timing depends on
 * the machine. `-Dcarmem.latency.budgetMs` (default 50) is the p99 limit per call. A head unit is
 * several times slower than a build machine, so read the printed table for the margin, and measure on
 * the target with the service's own timers (`ICarMemory.stats`).
 */
@Tag("latency")
class LatencyTest {
    private val budgetMs = (System.getProperty("carmem.latency.budgetMs") ?: "50").toDouble()
    private val weeks = (System.getProperty("carmem.latency.weeks") ?: "26").toInt()
    private val start: LocalDateTime = LocalDateTime.of(2026, 3, 2, 7, 40) // a Monday

    private val chatter = listOf(
        "what's the weather like today", "play something relaxing", "how long until we get there", "call the office",
        "is there a petrol station nearby", "read my last message", "turn the volume down a bit", "what's on my calendar",
    )

    private fun seed(mem: CarMemory): LocalDateTime {
        mem.enroll("asha", "Asha", listOf("key_fob" to "fob-a")); mem.enroll("ben", "Ben", listOf("key_fob" to "fob-b"))
        var said = 0
        for (w in 0 until weeks) for (d in 0 until 7) for ((who, fob) in listOf("asha" to "fob-a", "ben" to "fob-b")) {
            val day = start.plusWeeks(w.toLong()).plusDays(d.toLong())
            mem.ignitionOn(listOf(IdentitySignal("key_fob", fob)), day.minusMinutes(5))
            val temp = mapOf("cabin_temp" to JsonPrimitive(if (who == "asha") 21.0 else 19.5), "media" to JsonPrimitive(if (who == "asha") "Jazz FM" else "News 24"))
            val legs = if (d < 5) {
                if (who == "asha") listOf(Triple(0L, "home", "Oakwood Primary"), Triple(25L, "Oakwood Primary", "Riverside Office"), Triple(590L, "Riverside Office", "home"))
                else listOf(Triple(40L, "home", "Lotus Yoga"), Triple(130L, "Lotus Yoga", "home"), Triple(600L, "home", "Greenway Market"), Triple(650L, "Greenway Market", "home"))
            } else listOf(Triple(180L, "home", "place-${(w * 7 + d) % 9}"), Triple(260L, "place-${(w * 7 + d) % 9}", "home"))
            for ((offset, from, to) in legs) {
                val t = day.plusMinutes(offset)
                mem.proactive(t.minusMinutes(2), from)
                mem.recordTrip(Trip(t, t.plusMinutes(15), from, to, listOf(who), temp, distanceKm = 6.0))
                repeat(3) { mem.hear(chatter[said++ % chatter.size], now = t.plusMinutes(3 + it.toLong())) }
            }
            if (d == 0) mem.hear("remember that ${listOf("the dentist", "the gym", "the garage", "grandma", "the vet")[w % 5]} appointment $w is on ${listOf("Tuesday", "Friday")[w % 2]} at ${w % 12 + 1}", now = day)
            mem.ignitionOff(day.plusHours(12))
        }
        val last = start.plusWeeks(weeks.toLong())
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), last.minusMinutes(5))
        mem.hear("my kid's school is Oakwood Primary", now = last.minusMinutes(4))
        mem.hear("I'm allergic to peanuts", now = last.minusMinutes(4))
        mem.hear("I prefer 21 degrees", now = last.minusMinutes(4))
        return last
    }

    private class Stat(val name: String, val ms: DoubleArray) {
        fun p(q: Double) = ms.sorted()[minOf(ms.size - 1, (q * ms.size).toInt())]
        override fun toString() = "%-34s p50 %7.2f  p95 %7.2f  p99 %7.2f  max %7.2f ms".format(name, p(0.50), p(0.95), p(0.99), ms.max())
    }

    private fun measure(name: String, n: Int = 200, warm: Int = 30, block: (Int) -> Unit): Stat {
        repeat(warm) { block(-1 - it) }
        return Stat(name, DoubleArray(n) { i -> val t = System.nanoTime(); block(i); (System.nanoTime() - t) / 1e6 })
    }

    @Test fun `every assistant call stays inside the latency budget on a six month database`() {
        val file = File.createTempFile("carmem-latency", ".db").also { it.delete() }
        val mem = CarMemory(JdbcSqlDriver("jdbc:sqlite:${file.absolutePath}"))
        try {
            val seedStart = System.nanoTime()
            val now = seed(mem)
            val counts = listOf("episodes", "facts", "routines", "audit").joinToString { "$it=${mem.store.count(it)}" }
            println("carmem latency  weeks=$weeks  $counts  db=${file.length() / 1024} KiB  seeded in ${(System.nanoTime() - seedStart) / 1_000_000} ms")
            val routineId = mem.proactive(now, "home").first { it.kind == "routine" }.recordId
            var tick = 0L
            fun t() = now.plusSeconds(++tick)

            val stats = listOf(
                measure("context(no query, local)") { mem.context("", "local", t()).let { it.render(); it.toJson().toString() } },
                measure("context(query, local)") { mem.context("where is my kid's school", "local", t()).let { it.render(); it.toJson().toString() } },
                measure("context(query, cloud)") { mem.context("what am I allergic to", "cloud", t()).let { it.render(); it.toJson().toString() } },
                measure("recall(query)") { mem.recall("dentist appointment", 6, t()).toString() },
                measure("proactive(location)") { mem.proactive(now, "home") },
                measure("explain(routine)") { mem.explain(routineId).toString() },
                measure("hear(nothing to store)") { mem.hear(chatter[Math.floorMod(it, chatter.size)], now = t()).toString() },
                measure("hear(stated preference)") { mem.hear("I prefer ${20 + Math.floorMod(it, 4)} degrees", now = t()).toString() },
                measure("hear(remember note)") { mem.hear("remember that the parking code for level ${Math.floorMod(it, 40)} is ${1000 + it}", now = t()).toString() },
                measure("hear(no) on a proposal") { mem.proactive(now, "home"); mem.hear("no", now = t()).toString(); mem.hear("yes", now = t()) },
                measure("recordTrip") { val s = now.plusMinutes(5L * Math.floorMod(it, 6)); mem.recordTrip(Trip(s, s.plusMinutes(15), "home", "Oakwood Primary", listOf("asha"), mapOf("cabin_temp" to JsonPrimitive(21.0)))) },
                measure("ignitionOff + ignitionOn", n = 40, warm = 5) { mem.ignitionOff(t()); mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t()) },
            )
            stats.forEach(::println)
            // A process restart on the head unit: nothing cached, first question of the drive.
            mem.close()
            val cold = CarMemory(JdbcSqlDriver("jdbc:sqlite:${file.absolutePath}"))
            cold.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t())
            val c0 = System.nanoTime(); cold.context("where is my kid's school", "local", t()).let { it.render(); it.toJson().toString() }
            println("%-34s %7.2f ms (once)".format("first context(query) after restart", (System.nanoTime() - c0) / 1e6))
            cold.close()
            val over = stats.filter { it.p(0.99) > budgetMs }
            assertTrue(over.isEmpty(), "p99 over ${budgetMs} ms: " + over.joinToString { it.name })
        } finally {
            runCatching { mem.close() }; file.delete(); File(file.absolutePath + "-wal").delete(); File(file.absolutePath + "-shm").delete()
        }
    }
}
