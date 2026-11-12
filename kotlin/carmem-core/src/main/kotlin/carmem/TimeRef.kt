package carmem

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The stretch of time a question about the past refers to: "last Friday", "yesterday evening", "two days ago".
 * Returns null when the text names no time. Ranges are half-open, [start, end).
 */
fun timeRange(text: String, now: LocalDateTime): Pair<LocalDateTime, LocalDateTime>? {
    val t = text.lowercase()
    val today = now.toLocalDate().atStartOfDay()
    fun day(start: LocalDateTime) = start to start.plusDays(1)
    fun part(range: Pair<LocalDateTime, LocalDateTime>): Pair<LocalDateTime, LocalDateTime> {
        val d = range.first
        return when {
            Regex("""\bmorning\b""").containsMatchIn(t) -> d.with(LocalTime.of(5, 0)) to d.with(LocalTime.of(11, 0))
            Regex("""\bafternoon\b""").containsMatchIn(t) -> d.with(LocalTime.of(11, 0)) to d.with(LocalTime.of(17, 0))
            Regex("""\bevening\b""").containsMatchIn(t) -> d.with(LocalTime.of(17, 0)) to d.with(LocalTime.of(22, 0))
            else -> range
        }
    }
    val words = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "couple of" to 2)
    fun num(s: String) = s.toIntOrNull() ?: words[s]

    if ("day before yesterday" in t) return part(day(today.minusDays(2)))
    if (Regex("""\blast night\b""").containsMatchIn(t)) return today.minusDays(1).with(LocalTime.of(18, 0)) to today.with(LocalTime.of(5, 0))
    if (Regex("""\byesterday\b""").containsMatchIn(t)) return part(day(today.minusDays(1)))
    if (Regex("""\btonight\b""").containsMatchIn(t)) return today.with(LocalTime.of(17, 0)) to today.plusDays(1)
    if (Regex("""\b(today|this (morning|afternoon|evening)|earlier)\b""").containsMatchIn(t)) return part(day(today))
    Regex("""\b(\d+|a|an|one|two|three|four|five|six|seven|couple of) (day|week)s? ago\b""").find(t)?.let { m ->
        val n = num(m.groupValues[1]) ?: return@let
        return if (m.groupValues[2] == "day") part(day(today.minusDays(n.toLong())))
        else today.minusWeeks(n.toLong()).with(DayOfWeek.MONDAY) to today.minusWeeks(n.toLong()).with(DayOfWeek.MONDAY).plusDays(7)
    }
    if (Regex("""\blast week\b""").containsMatchIn(t)) return today.minusWeeks(1).with(DayOfWeek.MONDAY) to today.with(DayOfWeek.MONDAY)
    if (Regex("""\bthis week\b""").containsMatchIn(t)) return today.with(DayOfWeek.MONDAY) to today.plusDays(1)
    if (Regex("""\b(last|at the|on the|over the) weekend\b""").containsMatchIn(t)) {
        // the most recent Saturday that has already finished or is today
        var sat = today.with(DayOfWeek.SATURDAY); if (sat.isAfter(today)) sat = sat.minusWeeks(1)
        if ("last" in t && sat == today) sat = sat.minusWeeks(1)
        return sat to sat.plusDays(2)
    }
    if (Regex("""\blast month\b""").containsMatchIn(t)) return today.withDayOfMonth(1).minusMonths(1) to today.withDayOfMonth(1)
    Regex("""\b(last |on |this past |from )?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""").find(t)?.let { m ->
        val dow = DayOfWeek.valueOf(m.groupValues[2].uppercase())
        var back = (today.dayOfWeek.value - dow.value + 7) % 7
        if (back == 0 && m.groupValues[1].startsWith("last")) back = 7   // "last Friday" said on a Friday is a week ago
        return part(day(today.minusDays(back.toLong())))
    }
    return null
}
