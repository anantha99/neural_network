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
    fun build(occ: Occupancy, query: String = "", destination: String = "local", now: LocalDateTime, kEpisodes: Int = 5, episodeDays: Int = 42): ContextPacket {
        val person = store.getPerson(occ.driverId)
        val name = person?.name ?: if (occ.isGuest) "Guest" else occ.driverId
        val packet = ContextPacket(occ.driverId, name, destination)
        packet.facts = dedupeFacts(store.facts(listOf(occ.driverId), states = listOf(State.CONFIRMED)))
        packet.routines = store.routines(listOf(occ.driverId), listOf(State.CONFIRMED, State.CANDIDATE))
        packet.vehicle = store.facts(listOf(VEHICLE_SCOPE), states = listOf(State.CONFIRMED))
        if (query.isNotEmpty()) {
            val qvec = embedder.embed(query)
            val hl = policy.retrieval_recency_half_life_days
            val cands = store.episodes(listOf(occ.driverId), since = now.minusDays(episodeDays.toLong()), limit = 400, withEmbeddings = true)
            val ranked = cands.map { (ep, vec) -> score(query, qvec, ep.text, vec) * recencyWeight(Duration.between(ep.ts, now).seconds / 86400.0, hl) to ep }
                .sortedByDescending { it.first }.take(kEpisodes)
            packet.episodes = ranked.filter { it.first > 0.05 }.map { it.second }
            packet.facts = packet.facts.sortedByDescending { score(query, qvec, it.describe(), null) }
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
