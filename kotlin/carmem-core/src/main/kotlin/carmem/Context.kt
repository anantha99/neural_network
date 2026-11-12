package carmem

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ContextPacket(val driverId: String, val driverName: String, val destination: String) {
    var facts: List<Fact> = emptyList()
    var routines: List<Routine> = emptyList()
    var vehicle: List<Fact> = emptyList()
    var episodes: List<Episode> = emptyList()
    /** Places the car knows by name. */
    var places: List<String> = emptyList()
    /** The car right now. Read when the packet was built; never stored. */
    var live: Map<String, String> = emptyMap()
    /** The last few turns and what a pronoun would refer to. In memory only. */
    var turns: List<Conversation.Turn> = emptyList()
    var focus: List<Conversation.Focus> = emptyList()
    val masked: LinkedHashMap<String, String> = linkedMapOf()  // token -> original

    private val epFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun render(): String {
        val lines = mutableListOf("[Driver memory for $driverName ($driverId); audience=$destination]")
        if (live.isNotEmpty()) { lines += "Right now (live, not stored):"; live.forEach { (k, v) -> lines += "- ${k.replace('_', ' ')}: ${maskText(v)}" } }
        if (turns.isNotEmpty()) { lines += "Conversation so far (this drive, not stored):"; turns.forEach { lines += "- ${it.speaker}: ${maskText(it.text)}" } }
        if (focus.isNotEmpty()) lines += "In focus (what \"there\", \"him\", \"it\" mean now): " + focus.joinToString("; ") { "${it.kind} = ${maskText(it.name)}" }
        if (facts.isNotEmpty()) { lines += "Known preferences and facts:"; facts.forEach { lines += "- ${factLine(it)}" } }
        if (places.isNotEmpty()) lines += "Known places: " + places.joinToString(", ") { maskText(it) }
        if (routines.isNotEmpty()) {
            lines += "Routines:"
            routines.forEach { r -> lines += "- ${maskText(r.describe())} [${r.state.wire}, ${r.stats["occurrences"]?.render() ?: "0"} times over ${r.stats["distinct_weeks"]?.render() ?: "0"} weeks]" }
        }
        if (vehicle.isNotEmpty()) { lines += "About this car (shared by all occupants):"; vehicle.forEach { lines += "- ${it.key.replace('_', ' ')}: ${it.value.render()} [${it.source}]" } }
        if (episodes.isNotEmpty()) { lines += "Relevant recent episodes:"; episodes.forEach { lines += "- ${it.ts.format(epFmt)} ${it.kind}: ${maskText(it.text)}" } }
        if (masked.isNotEmpty()) lines += "(${masked.size} personal field(s) masked for cloud; tokens are stable per session)"
        return lines.joinToString("\n")
    }

    fun unmask(text: String): String { var t = text; masked.forEach { (tok, orig) -> t = t.replace(tok, orig) }; return t }

    fun toJson(): JsonObject = buildJsonObject {
        put("driver_id", driverId); put("destination", destination)
        put("facts", JsonArray(facts.map { f -> buildJsonObject { put("id", f.id); put("text", factLine(f)); put("source", f.source); put("state", f.state.wire); put("sensitivity", f.sensitivity) } }))
        put("routines", JsonArray(routines.map { r -> buildJsonObject { put("id", r.id); put("text", maskText(r.describe())); put("state", r.state.wire); r.stats.forEach { (k, v) -> put(k, v) } } }))
        put("vehicle", JsonArray(vehicle.map { f -> buildJsonObject { put("id", f.id); put("key", f.key); put("value", f.value); put("source", f.source) } }))
        put("episodes", JsonArray(episodes.map { e -> buildJsonObject { put("id", e.id); put("ts", iso(e.ts)); put("kind", e.kind); put("text", maskText(e.text)) } }))
        put("places", JsonArray(places.map { JsonPrimitive(maskText(it)) }))
        put("live", JsonObject(live.mapValues { JsonPrimitive(maskText(it.value)) }))
        put("conversation", JsonArray(turns.map { t -> buildJsonObject { put("ts", iso(t.ts)); put("speaker", t.speaker); put("text", maskText(t.text)) } }))
        put("focus", JsonArray(focus.map { f -> buildJsonObject { put("kind", f.kind); put("name", maskText(f.name)) } }))
        put("masked_count", masked.size)
    }

    private fun tokenFor(f: Fact): String? = masked.entries.firstOrNull { it.value == f.value.render() && it.key.startsWith("<${f.category}") }?.key

    private fun factLine(f: Fact): String {
        val token = tokenFor(f)
        val v = token ?: f.value.render()
        // A note's key is made from its text and a place's key is its name, so a masked one must not show its key either.
        val key = masked.entries.firstOrNull { it.value.equals(f.key, ignoreCase = true) }?.key
            ?: if (token != null && f.key == slug(f.value.render())) "(masked)" else f.key
        val cond = f.condition?.let { " (when ${it.replace("=", " is ")})" } ?: ""
        return "${f.category}/$key = $v$cond [${f.source}, ${f.state.wire}, id=${f.id}]"
    }

    /** Replace every masked original that appears as a whole word or phrase, longest first, whatever its case. */
    private fun maskText(text: String): String {
        if (masked.isEmpty()) return text
        var t = text
        for ((tok, orig) in masked.entries.sortedByDescending { it.value.length }) {
            if (orig.isEmpty() || !t.contains(orig, ignoreCase = true)) continue
            t = Regex("(?<![\\w<])" + Regex.escape(orig) + "(?![\\w>])", RegexOption.IGNORE_CASE).replace(t) { tok }
        }
        return t
    }
}

class ContextBuilder(private val store: Store, private val policy: Policy, private val embedder: Embedder) {
    /** Kind, tokens and vector of an episode. Episodes are immutable, so these are computed or read once per process. */
    private class Ranked(val kind: String, val tokens: Set<String>, val vector: DoubleArray?)
    private val ranked = object : LinkedHashMap<Long, Ranked>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Ranked>?) = size > 4096
    }
    /** Call after anything rewrites or deletes episodes in place. */
    fun invalidate() { ranked.clear(); semantic = null }

    /** The driver's facts and routines as last read, reused until the store says one of them changed. */
    class Semantic(val driver: String, val version: Long, val name: String?, val facts: List<Fact>, val routines: List<Routine>, val vehicle: List<Fact>)
    private var semantic: Semantic? = null
    fun semanticFor(driver: String): Semantic {
        semantic?.let { if (it.driver == driver && it.version == store.semanticVersion) return it }
        return Semantic(driver, store.semanticVersion, store.getPerson(driver)?.name,
            dedupeFacts(store.facts(listOf(driver), states = listOf(State.CONFIRMED))),
            store.routines(listOf(driver), listOf(State.CONFIRMED, State.CANDIDATE)),
            store.facts(listOf(VEHICLE_SCOPE), states = listOf(State.CONFIRMED))).also { semantic = it }
    }

    fun build(
        occ: Occupancy, query: String = "", destination: String = "local", now: LocalDateTime, kEpisodes: Int = 5, episodeDays: Int = 42,
        places: List<String> = emptyList(), live: Map<String, String> = emptyMap(),
        turns: List<Conversation.Turn> = emptyList(), focus: List<Conversation.Focus> = emptyList(),
    ): ContextPacket {
        val sem = semanticFor(occ.driverId)
        val name = sem.name ?: if (occ.isGuest) "Guest" else occ.driverId
        val packet = ContextPacket(occ.driverId, name, destination)
        packet.facts = sem.facts
        packet.routines = sem.routines
        packet.vehicle = sem.vehicle
        packet.places = places; packet.live = live; packet.turns = turns; packet.focus = focus
        if (query.isNotEmpty()) {
            val qtok = tokenize(query).toSet(); val qvec = embedder.embed(query)
            val hl = policy.retrieval_recency_half_life_days
            // "Last Friday", "yesterday evening": look only there, and let what happened then through even when it shares
            // no word with the question ("where did I go last Friday" against "trip home -> Luigi's").
            val range = timeRange(query, now)
            val cands = if (range != null) store.episodeIndex(listOf(occ.driverId), range.first, 400, range.second)
                else store.episodeIndex(listOf(occ.driverId), now.minusDays(episodeDays.toLong()), 400)
            val missing = cands.map { it.first }.filter { it !in ranked }
            if (missing.isNotEmpty()) store.episodeVectors(missing).forEach { (id, r) -> ranked[id] = Ranked(r.first, tokenize(r.second).toSet(), r.third) }
            val top = cands.mapNotNull { (id, ts) ->
                val r = ranked[id] ?: return@mapNotNull null
                val overlap = if (qtok.isEmpty()) 0.0 else qtok.count { it in r.tokens }.toDouble() / qtok.size
                val match = overlap + (r.vector?.let { 0.7 * cosine(qvec, it) } ?: 0.0)
                (if (range != null) match + (if (r.kind == "trip") 0.6 else 0.4)
                 else match * recencyWeight(Duration.between(ts, now).seconds / 86400.0, hl)) to id
            }.sortedByDescending { it.first }.take(if (range != null) maxOf(kEpisodes, 8) else kEpisodes)
            packet.episodes = top.filter { it.first > 0.05 }.mapNotNull { store.getEpisode(it.second) }
            packet.facts = packet.facts.sortedByDescending { score(qtok, qvec, it.describe(), null) }
        }
        if (destination == "cloud") {
            var n = 0
            for (f in packet.facts) if (policy.maskedInCloud(f.category, f.sensitivity)) { n++; packet.masked["<${f.category}_$n>"] = f.value.render() }
            // Where the driver lives, works and visits is as private as anything they say: the cloud sees <place_1>.
            if (policy.maskedInCloud("place", policy.sensitivityFor("place")))
                for (p in packet.facts.filter { it.category == "place" }.map { it.key } + places)
                    if (p.isNotBlank() && packet.masked.values.none { it.equals(p, ignoreCase = true) }) { n++; packet.masked["<place_$n>"] = p }
        }
        return packet
    }

    private fun dedupeFacts(facts: List<Fact>): List<Fact> {
        val rank = mapOf("stated" to 2, "sensor" to 1, "implicit" to 0)
        val best = linkedMapOf<Triple<String, String, String?>, Fact>()
        for (f in facts) { val k = Triple(f.category, f.key, f.condition); val cur = best[k]; if (cur == null || (rank[f.source] ?: 0) > (rank[cur.source] ?: 0)) best[k] = f }
        val withCond = best.keys.filter { it.third != null }.map { it.first to it.second }.toSet()
        return best.filter { (k, f) -> !(k.third == null && f.source == "implicit" && (k.first to k.second) in withCond) }.values.toList()
    }
}
