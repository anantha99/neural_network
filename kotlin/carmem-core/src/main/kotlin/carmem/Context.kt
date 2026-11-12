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
    val masked: LinkedHashMap<String, String> = linkedMapOf()  // token -> original

    private val epFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun render(): String {
        val lines = mutableListOf("[Driver memory for $driverName ($driverId); audience=$destination]")
        if (facts.isNotEmpty()) { lines += "Known preferences and facts:"; facts.forEach { lines += "- ${factLine(it)}" } }
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
        put("facts", JsonArray(facts.map { f -> buildJsonObject { put("id", f.id); put("text", factLine(f)); put("source", f.source); put("state", f.state.wire) } }))
        put("routines", JsonArray(routines.map { r -> buildJsonObject { put("id", r.id); put("text", maskText(r.describe())); put("state", r.state.wire); r.stats.forEach { (k, v) -> put(k, v) } } }))
        put("vehicle", JsonArray(vehicle.map { f -> buildJsonObject { put("id", f.id); put("key", f.key); put("value", f.value); put("source", f.source) } }))
        put("episodes", JsonArray(episodes.map { e -> buildJsonObject { put("id", e.id); put("ts", iso(e.ts)); put("kind", e.kind); put("text", maskText(e.text)) } }))
        put("masked_count", masked.size)
    }

    private fun factLine(f: Fact): String {
        val v = masked.entries.firstOrNull { it.value == f.value.render() && it.key.startsWith("<${f.category}") }?.key ?: f.value.render()
        val cond = f.condition?.let { " (when ${it.replace("=", " is ")})" } ?: ""
        return "${f.category}/${f.key} = $v$cond [${f.source}, ${f.state.wire}, id=${f.id}]"
    }

    private fun maskText(text: String): String { var t = text; masked.forEach { (tok, orig) -> if (orig.isNotEmpty() && orig in t) t = t.replace(orig, tok) }; return t }
}

class ContextBuilder(private val store: Store, private val policy: Policy, private val embedder: Embedder) {
    /** Tokens and vector of an episode. Episodes are immutable, so these are computed or read once per process. */
    private class Ranked(val tokens: Set<String>, val vector: DoubleArray?)
    private val ranked = object : LinkedHashMap<Long, Ranked>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Ranked>?) = size > 4096
    }
    /** Call after anything rewrites episode text in place. */
    fun invalidate() { ranked.clear(); semantic = null }

    /** The driver's facts and routines as last read, reused until the store says one of them changed. */
    private class Semantic(val driver: String, val version: Long, val name: String?, val facts: List<Fact>, val routines: List<Routine>, val vehicle: List<Fact>)
    private var semantic: Semantic? = null
    private fun semanticFor(driver: String): Semantic {
        semantic?.let { if (it.driver == driver && it.version == store.semanticVersion) return it }
        return Semantic(driver, store.semanticVersion, store.getPerson(driver)?.name,
            dedupeFacts(store.facts(listOf(driver), states = listOf(State.CONFIRMED))),
            store.routines(listOf(driver), listOf(State.CONFIRMED, State.CANDIDATE)),
            store.facts(listOf(VEHICLE_SCOPE), states = listOf(State.CONFIRMED))).also { semantic = it }
    }

    fun build(occ: Occupancy, query: String = "", destination: String = "local", now: LocalDateTime, kEpisodes: Int = 5, episodeDays: Int = 42): ContextPacket {
        val sem = semanticFor(occ.driverId)
        val name = sem.name ?: if (occ.isGuest) "Guest" else occ.driverId
        val packet = ContextPacket(occ.driverId, name, destination)
        packet.facts = sem.facts
        packet.routines = sem.routines
        packet.vehicle = sem.vehicle
        if (query.isNotEmpty()) {
            val qtok = tokenize(query).toSet(); val qvec = embedder.embed(query)
            val hl = policy.retrieval_recency_half_life_days
            val cands = store.episodeIndex(listOf(occ.driverId), now.minusDays(episodeDays.toLong()), 400)
            val missing = cands.map { it.first }.filter { it !in ranked }
            if (missing.isNotEmpty()) store.episodeVectors(missing).forEach { (id, tv) -> ranked[id] = Ranked(tokenize(tv.first).toSet(), tv.second) }
            val top = cands.mapNotNull { (id, ts) ->
                val r = ranked[id] ?: return@mapNotNull null
                val overlap = if (qtok.isEmpty()) 0.0 else qtok.count { it in r.tokens }.toDouble() / qtok.size
                (overlap + (r.vector?.let { 0.7 * cosine(qvec, it) } ?: 0.0)) * recencyWeight(Duration.between(ts, now).seconds / 86400.0, hl) to id
            }.sortedByDescending { it.first }.take(kEpisodes)
            packet.episodes = top.filter { it.first > 0.05 }.mapNotNull { store.getEpisode(it.second) }
            packet.facts = packet.facts.sortedByDescending { score(qtok, qvec, it.describe(), null) }
        }
        if (destination == "cloud") {
            var n = 0
            for (f in packet.facts) if (policy.maskedInCloud(f.category, f.sensitivity)) { n++; packet.masked["<${f.category}_$n>"] = f.value.render() }
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
