package carmem

import java.time.Duration
import java.time.LocalDateTime

/**
 * What was just said, and what "there" and "him" mean right now.
 *
 * Kept in memory only, for the current drive and no longer than [ttl]: it is never written to the
 * database, and it is gone at ignition off. The lasting record of an utterance is its episode.
 */
class Conversation(private val ttl: Duration, private val maxTurns: Int) {
    data class Turn(val ts: LocalDateTime, val speaker: String, val text: String)
    data class Focus(val ts: LocalDateTime, val kind: String, val name: String)

    private val turns = ArrayDeque<Turn>()
    private val focus = ArrayDeque<Focus>()

    private fun prune(now: LocalDateTime) {
        val cutoff = now.minus(ttl)
        while (turns.isNotEmpty() && (turns.first().ts.isBefore(cutoff) || turns.size > maxTurns)) turns.removeFirst()
        while (focus.isNotEmpty() && (focus.first().ts.isBefore(cutoff) || focus.size > maxTurns * 2)) focus.removeFirst()
    }

    fun add(ts: LocalDateTime, speaker: String, text: String) { if (text.isNotBlank()) { turns += Turn(ts, speaker, text.trim()); prune(ts) } }

    /** Something concrete came up: a place, a person, a thing. The latest of each kind is what a pronoun refers to. */
    fun mention(ts: LocalDateTime, kind: String, name: String) {
        if (name.isBlank()) return
        focus.removeAll { it.kind == kind && it.name.equals(name, ignoreCase = true) }
        focus += Focus(ts, kind, name.trim()); prune(ts)
    }

    fun turns(now: LocalDateTime): List<Turn> { prune(now); return turns.toList() }

    /** Latest entity of each kind, most recent first. */
    fun inFocus(now: LocalDateTime): List<Focus> { prune(now); return focus.reversed().distinctBy { it.kind } }

    /**
     * What the referring words in [text] point at: "there" to the latest place, "him" / "her" / "them" to the latest
     * person, a bare "it" or "that" to whatever came up last. Empty when the text refers to nothing in focus.
     */
    fun resolve(text: String, now: LocalDateTime): Map<String, Focus> {
        val current = inFocus(now); val out = linkedMapOf<String, Focus>()
        PLACE_REF.find(text)?.let { m -> current.firstOrNull { it.kind == "place" }?.let { out[m.value.lowercase()] = it } }
        PERSON_REF.find(text)?.let { m -> current.firstOrNull { it.kind == "person" }?.let { out[m.value.lowercase()] = it } }
        if (out.isEmpty()) THING_REF.find(text)?.let { m -> current.firstOrNull()?.let { out[m.value.lowercase()] = it } }
        return out
    }

    fun clear() { turns.clear(); focus.clear() }

    private companion object {
        val PLACE_REF = Regex("""\b(back there|there|that place|the same place|that address)\b""", RegexOption.IGNORE_CASE)
        val PERSON_REF = Regex("""\b(him|her|them|that person|he|she|they)\b""", RegexOption.IGNORE_CASE)
        val THING_REF = Regex("""\b(that one|this one|it|that)\b""", RegexOption.IGNORE_CASE)
    }
}
