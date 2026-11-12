package carmem

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime

/** Cabin settings the car can observe and apply, by key. */
internal val SETTING_CATEGORIES =mapOf("cabin_temp" to "climate", "seat_position" to "seat", "media" to "media", "fan" to "climate")

private class Session(val occupancy: Occupancy, var started: LocalDateTime) {
    val pending = mutableListOf<Proposal>()
    val suppressed = mutableSetOf<String>()
    val lastStated = mutableListOf<String>()
}

/**
 * The facade an assistant talks to. One instance per vehicle; owns the store,
 * the identity resolver, the consolidator and the current driving session.
 * Mirrors carmem/memory.py.
 */
class CarMemory(
    driver: SqlDriver,
    val policy: Policy = Policy(),
    llm: LlmClient? = null,
    val embedder: Embedder = HashingEmbedder(),
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) {
    val store = Store(driver)
    val identity = IdentityResolver(store, policy)
    val consolidator = Consolidator(store, policy, embedder, llm)
    val contextBuilder = ContextBuilder(store, policy, embedder)
    private val extractor: Extractor = if (llm != null) LlmExtractor(llm) else RuleExtractor()
    private var session: Session? = null

    // ------------------------------------------------------------ identity / session
    // Every call that writes runs as one transaction: one commit per call, and nothing half applied if it fails.
    fun enroll(id: String, name: String, bindings: List<Pair<String, String>> = emptyList()): Person = store.tx { Person(id, name).also { identity.enroll(it, bindings) } }
    fun bind(kind: String, value: String, personId: String) { requireNotNull(store.getPerson(personId)) { "unknown person $personId" }; store.bindIdentity(kind, value, personId) }

    fun ignitionOn(signals: List<IdentitySignal>, now: LocalDateTime = clock()): Occupancy = store.tx {
        if (session != null) ignitionOff(now)
        val occ = identity.resolve(signals, now)
        session = Session(occ, now)
        occ
    }

    fun ignitionOff(now: LocalDateTime = clock()): JsonObject = ignitionOffTx(now).also { store.checkpoint() }

    private fun ignitionOffTx(now: LocalDateTime): JsonObject = store.tx {
        val wiped = linkedMapOf<String, Map<String, Int>>()
        session?.occupancy?.scopes?.filter(::isGuest)?.forEach { wiped[it] = store.purgeScope(it); store.audit(now, it, "guest.wiped") }
        store.scopesPresent().filter { isGuest(it) && it !in wiped }.forEach { wiped[it] = store.purgeScope(it) }
        session = null
        val maint = consolidator.maintenance(now)
        buildJsonObject {
            put("wiped", JsonObject(wiped.mapValues { (_, m) -> JsonObject(m.mapValues { JsonPrimitive(it.value) }) }))
            put("maintenance", JsonObject(maint.mapValues { JsonPrimitive(it.value) }))
        }
    }

    val occupancy: Occupancy? get() = session?.occupancy
    private fun driver(): String = session?.occupancy?.driverId ?: error("no session: call ignitionOn() first")

    // ------------------------------------------------------------ write side
    fun recordTrip(trip: Trip, now: LocalDateTime = trip.end): JsonObject = store.tx {
        val scope = session?.occupancy?.driverId ?: trip.occupants.firstOrNull() ?: error("recordTrip needs a session or trip.occupants")
        val text = trip.summary()
        val ep = store.addEpisode(scope, "trip", trip.start, text, buildJsonObject {
            put("origin", trip.origin); put("destination", trip.destination); put("end", iso(trip.end))
            put("settings", JsonObject(trip.settings)); put("route", JsonObject(trip.route))
            put("occupants", JsonArray(trip.occupants.map { JsonPrimitive(it) })); put("distance_km", trip.distanceKm)
        }, embedder.embed(text))
        val facts = mutableListOf<JsonElement>()
        for ((key, value) in trip.settings) {
            val cat = SETTING_CATEGORIES[key] ?: continue
            if (consolidator.factTombstoned(scope, cat, key, now)) continue
            val f = consolidator.observeSetting(scope, cat, key, value, ep, now)
            facts += buildJsonObject { put("id", f.id); put("state", f.state.wire); put("fact", f.describe()) }
        }
        val rt = consolidator.observeTrip(scope, trip, ep, now)
        val accepted = mutableListOf<String>()
        session?.let { s ->
            for (p in s.pending.toList()) if (p.kind == "routine" && normPlace(p.action["destination"]?.render() ?: "") == normPlace(trip.destination)) {
                consolidator.accept(p, ep, now); accepted += p.recordId; s.pending.remove(p)
            }
        }
        buildJsonObject {
            put("episode", ep.id); put("facts", JsonArray(facts))
            put("routine", rt?.let { r -> buildJsonObject { put("id", r.id); put("state", r.state.wire); put("routine", r.describe()); r.stats.forEach { (k, v) -> put(k, v) } } } ?: JsonNull)
            put("accepted", JsonArray(accepted.map { JsonPrimitive(it) }))
        }
    }

    fun recordVehicleEvent(key: String, value: JsonElement, category: String = "vehicle_fault", now: LocalDateTime = clock(), text: String? = null): Fact = store.tx {
        val t = text ?: "vehicle $category $key: ${value.render()}"
        val ep = store.addEpisode(VEHICLE_SCOPE, "sensor", now, t, buildJsonObject { put("key", key); put("value", value) }, embedder.embed(t))
        consolidator.sensorFact(category, key, value, ep, now)
    }

    /** Process one utterance: driving-time intents first, then extraction. */
    fun hear(text: String, speakerId: String? = null, now: LocalDateTime = clock()): JsonObject {
        val scope = speakerId ?: driver()
        // The utterance is committed first, and a model-backed extractor runs outside any transaction: it can take seconds.
        val ep = store.addEpisode(scope, "utterance", now, text, buildJsonObject { put("speaker", scope) }, embedder.embed(text))
        val intent = detectIntent(text)
        val candidates = if (intent.name == "none") extractor.extract(text) else emptyList()
        val s = session
        return store.tx { buildJsonObject {
            put("intent", intent.name); put("episode", ep.id)
            when (intent.name) {
                "reject" -> {
                    val target = activeProposal(Tier.SUGGEST)
                    if (target != null && s != null) { put("correction", consolidator.reject(target, ep, now)); s.suppressed += target.recordId; s.pending.remove(target) }
                    else put("correction", buildJsonObject { put("applied", false); put("reason", "nothing pending") })
                    put("stored", JsonArray(emptyList()))
                }
                "accept" -> {
                    val target = activeProposal(Tier.SUGGEST)
                    if (target != null && s != null) { put("correction", consolidator.accept(target, ep, now)); s.pending.remove(target) }
                    put("stored", JsonArray(emptyList()))
                }
                "forget" -> {
                    if (intent.target == null) {
                        val target = activeProposal(Tier.SUGGEST)
                        val rid = target?.recordId ?: s?.lastStated?.lastOrNull()
                        put("forget", if (rid != null) forget(rid, now) else buildJsonObject { put("forgot", JsonNull); put("reason", "nothing to forget") })
                        if (target != null && s != null) { s.pending.remove(target); s.suppressed += target.recordId }
                    } else put("forget", forget(intent.target, now, scope))
                    put("stored", JsonArray(emptyList()))
                }
                "explain" -> {
                    val rid = activeProposal(Tier.SUGGEST)?.recordId ?: s?.lastStated?.lastOrNull()
                    put("explanation", if (rid != null) explain(rid) else buildJsonObject { put("record", JsonNull) })
                    put("stored", JsonArray(emptyList()))
                }
                else -> put("stored", JsonArray(candidates.map { applyCandidate(scope, it, ep, now) }))
            }
        } }
    }

    private fun applyCandidate(scope: String, c: Candidate, ep: Episode, now: LocalDateTime): JsonObject {
        val f = if (c.kind == "observed") {
            if (consolidator.factTombstoned(scope, c.category, c.key, now)) return buildJsonObject { put("skipped", "tombstoned"); put("fact", "${c.category}/${c.key}") }
            consolidator.observeSetting(scope, c.category, c.key, c.value, ep, now)
        } else {
            val sig = "${c.category}/${c.key}"
            if (store.isTombstoned(scope, sig, now)) store.addTombstone(scope, sig, now, "restated by driver")
            consolidator.stateFact(scope, c, ep, now).also { f -> session?.let { if (scope in it.occupancy.scopes) it.lastStated += f.id } }
        }
        return buildJsonObject { put("id", f.id); put("state", f.state.wire); put("fact", f.describe()); put("source", f.source) }
    }

    // ------------------------------------------------------------ acting
    /** What the car would do now. Pass `moving = true` while the car is in motion: see [Policy.act_blocked_while_moving]. */
    fun proactive(now: LocalDateTime = clock(), location: String? = null, moving: Boolean = false): List<Proposal> = store.tx {
        val s = session ?: return@tx emptyList()
        val props = consolidator.proposals(driver(), now, s.suppressed, location, moving)
        val known = props.map { it.recordId }.toSet()
        s.pending.retainAll { it.recordId !in known }; s.pending += props
        if (props.isNotEmpty()) store.audit(now, driver(), "proactive", JsonArray(props.map { JsonPrimitive(it.text) }))
        props
    }

    private fun activeProposal(prefer: Tier): Proposal? {
        val s = session ?: return null
        if (s.pending.isEmpty()) return null
        return s.pending.filter { it.tier == prefer }.ifEmpty { s.pending }.last()
    }

    fun correct(answer: String, now: LocalDateTime = clock()) = hear(if (answer.trim().lowercase().startsWith("n")) "no" else "yes", now = now)

    // ------------------------------------------------------------ read side
    fun context(query: String = "", destination: String = "local", now: LocalDateTime = clock()): ContextPacket =
        contextBuilder.build(session?.occupancy ?: Occupancy("nobody", isGuest = true), query, destination, now)

    fun recall(query: String, k: Int = 6, now: LocalDateTime = clock()): JsonObject {
        val d = context(query, "local", now).toJson()
        return JsonObject(d.toMutableMap().apply {
            this["facts"] = JsonArray((d["facts"] as JsonArray).take(k)); this["episodes"] = JsonArray((d["episodes"] as JsonArray).take(k))
        })
    }

    fun factHistory(category: String, key: String, scope: String? = null): List<Fact> = store.factTimeline(scope ?: driver(), category, key)

    fun explain(recordId: String): JsonObject {
        val f = store.getFact(recordId); val r = store.getRoutine(recordId)
        if (f == null && r == null) return buildJsonObject { put("record", JsonNull) }
        fun ev(e: Evidence) = buildJsonObject { put("ts", e.ts); put("note", e.note); put("value", e.value); put("episode", store.getEpisode(e.episode_id)?.text ?: "(episode expired from episodic memory)") }
        val support = (f?.support ?: r!!.support).takeLast(10); val against = (f?.contradictions ?: r!!.contradictions).takeLast(10)
        val category = f?.category ?: r!!.category; val demotion = f?.demotion ?: r!!.demotion
        return buildJsonObject {
            put("record", recordId); put("kind", if (r != null) "routine" else "fact"); put("description", f?.describe() ?: r!!.describe())
            put("state", (f?.state ?: r!!.state).wire); put("source", f?.source ?: "trips")
            put("valid_from", f?.validFrom); put("valid_until", f?.validUntil)
            put("because", JsonArray(support.map(::ev))); put("against", JsonArray(against.map(::ev)))
            put("tier", policy.effectiveTier(category, demotion).wire)
        }
    }

    /** Forget by record id, or by free-text search within the speaker's scope. */
    fun forget(target: String, now: LocalDateTime = clock(), scope: String? = null): JsonObject = store.tx {
        if (store.getFact(target) != null || store.getRoutine(target) != null) return@tx consolidator.forget(target, now)
        val sc = scope ?: session?.occupancy?.driverId ?: return@tx buildJsonObject { put("forgot", JsonNull); put("reason", "no scope") }
        var best: String? = null; var bestS = 0.0
        for (f in store.facts(listOf(sc))) { val s = keywordOverlap(target, "${f.category} ${f.key} ${f.value.render()} ${f.condition ?: ""}".replace('_', ' ')); if (s > bestS) { best = f.id; bestS = s } }
        for (r in store.routines(listOf(sc))) { val s = keywordOverlap(target, "${r.destination} ${r.slotText()} route trip routine go going"); if (s > bestS) { best = r.id; bestS = s } }
        if (best == null || bestS < 0.2) return@tx buildJsonObject { put("forgot", JsonNull); put("reason", "no matching memory"); put("query", target) }
        consolidator.forget(best!!, now)
    }

    /**
     * The assistant decided this is worth keeping. Known patterns become typed facts as in [hear]; anything else is kept
     * as a note rather than dropped, because the caller's model has already judged it durable.
     */
    fun remember(text: String, speakerId: String? = null, now: LocalDateTime = clock()): JsonObject {
        val heard = hear(text, speakerId, now)
        val stored = heard["stored"] as? JsonArray
        if ((heard["intent"] as? JsonPrimitive)?.content != "none" || stored == null || stored.isNotEmpty() || text.isBlank()) return heard
        val scope = speakerId ?: driver()
        val ep = store.getEpisode((heard["episode"] as JsonPrimitive).content.toLong()) ?: return heard
        val note = text.trim().trimEnd('.', '!')
        val saved = store.tx { applyCandidate(scope, Candidate(noteCategory(note), slug(note), JsonPrimitive(note)), ep, now) }
        return JsonObject(heard + ("stored" to JsonArray(listOf(saved))))
    }

    /**
     * A place has a new name: the car worked out that `place-3` is home, or the driver named it. Everything learned
     * under the old name follows it (routines with their evidence, 'forget' tombstones, trip history), so learning
     * does not start again and the old name does not linger in what the assistant is told.
     */
    fun renamePlace(old: String, new: String, now: LocalDateTime = clock()): JsonObject = store.tx {
        val o = normPlace(old)
        var routines = 0; var episodes = 0
        if (o.isNotEmpty() && normPlace(new).isNotEmpty() && o != normPlace(new)) {
            fun swap(p: String) = if (normPlace(p) == o) new else p
            fun swap(e: Evidence) = e.copy(value = (e.value as? JsonPrimitive)?.takeIf { it.isString && normPlace(it.content) == o }?.let { JsonPrimitive(new) } ?: e.value, condition = e.condition?.let(::swap))
            val tol = policy.routine_slot_tolerance_min
            for (r in store.routines(null)) {
                val isDest = normPlace(r.destination) == o
                if (!isDest && normPlace(r.origin) != o && (r.support + r.contradictions).none { e -> normPlace(e.condition ?: "") == o || (e.value as? JsonPrimitive)?.let { it.isString && normPlace(it.content) == o } == true }) continue
                val dest = swap(r.destination)
                val moved = r.copy(destination = dest, signature = routineSignature(r.dow, dest), origin = swap(r.origin),
                    action = if ("destination" in r.action) r.action + ("destination" to JsonPrimitive(dest)) else r.action,
                    support = r.support.map(::swap).toMutableList(), contradictions = r.contradictions.map(::swap).toMutableList())
                // trips already recorded under the new name in the same slot are the same habit: one routine, not two
                val twin = if (isDest && r.state != State.RETIRED) store.routines(listOf(r.scope), signature = moved.signature)
                    .firstOrNull { it.id != r.id && it.state != State.RETIRED && kotlin.math.abs(it.minuteOfDay - r.minuteOfDay) <= tol } else null
                if (twin != null) {
                    store.deleteRoutine(r.id)
                    val support = (twin.support + moved.support).sortedBy { it.ts }
                    // each half saw the other's trips as "went elsewhere"; on a day with a trip to this place that is no longer true
                    val tripDays = support.filter { it.note == "trip" }.map { it.ts.take(10) }.toSet()
                    consolidator.evaluateRoutine(twin.copy(firstSeen = minOf(twin.firstSeen, moved.firstSeen), lastSeen = maxOf(twin.lastSeen, moved.lastSeen),
                        support = support.toMutableList(),
                        contradictions = (twin.contradictions + moved.contradictions).filterNot { it.note == "went elsewhere" && it.ts.take(10) in tripDays }.sortedBy { it.ts }.toMutableList(),
                        demotion = maxOf(twin.demotion, moved.demotion)), now)
                } else consolidator.evaluateRoutine(moved, now)
                routines++
            }
            store.renameTombstones(routineTombstoneKey(old), routineTombstoneKey(new))
            for (ep in store.episodesOfKind("trip")) {
                val p = ep.payload as? JsonObject ?: continue
                val from = (p["origin"] as? JsonPrimitive)?.content ?: continue; val to = (p["destination"] as? JsonPrimitive)?.content ?: continue
                if (normPlace(from) != o && normPlace(to) != o) continue
                val text = ep.text.replaceFirst("trip $from -> $to", "trip ${swap(from)} -> ${swap(to)}")
                store.rewriteEpisode(ep.id, text, JsonObject(p + mapOf("origin" to JsonPrimitive(swap(from)), "destination" to JsonPrimitive(swap(to)))), embedder.embed(text))
                episodes++
            }
            session?.pending?.replaceAll { p ->
                val d = (p.action["destination"] as? JsonPrimitive)?.content
                if (d != null && normPlace(d) == o) p.copy(text = p.text.replace(d, new), action = p.action + ("destination" to JsonPrimitive(new))) else p
            }
            contextBuilder.invalidate()
            store.audit(now, null, "place.renamed", buildJsonObject { put("from", old); put("to", new); put("routines", routines); put("episodes", episodes) })
        }
        buildJsonObject { put("from", old); put("to", new); put("routines", routines); put("episodes", episodes) }
    }

    // ------------------------------------------------------------ tools
    fun toolSpecs(flavor: String = "neutral"): List<JsonObject> = when (flavor) { "anthropic" -> MemoryTools.anthropic(); "openai" -> MemoryTools.openai(); else -> MemoryTools.neutral }

    fun handleTool(name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject {
        fun str(k: String) = (args[k] as? JsonPrimitive)?.content
        return when (name) {
            "memory_recall" -> recall(str("query") ?: "", str("k")?.toIntOrNull() ?: 6)
            "memory_remember" -> remember(str("text") ?: "")
            "memory_forget" -> forget(str("target") ?: "")
            "memory_correct" -> correct(str("answer") ?: "no")
            "memory_explain" -> explain(str("record_id") ?: "")
            "memory_proactive" -> buildJsonObject { put("proposals", JsonArray(proactive(location = str("location")).map { p -> buildJsonObject { put("id", p.id); put("kind", p.kind); put("record_id", p.recordId); put("scope", p.scope); put("tier", p.tier.wire); put("text", p.text); put("action", JsonObject(p.action)) } })) }
            else -> throw IllegalArgumentException("unknown tool $name")
        }
    }

    fun maintenance(now: LocalDateTime = clock()) = store.tx { consolidator.maintenance(now) }

    /** Semantic + procedural layers for sync. Episodic never leaves the car. */
    fun snapshot(forCloud: Boolean = false): JsonObject = buildJsonObject {
        put("facts", JsonArray(store.facts(null, states = listOf(State.CONFIRMED, State.CANDIDATE))
            .filter { !isGuest(it.scope) && !(forCloud && policy.maskedInCloud(it.category, it.sensitivity)) }
            .map { f -> buildJsonObject { put("id", f.id); put("scope", f.scope); put("fact", f.describe()); put("source", f.source); put("state", f.state.wire) } }))
        put("routines", JsonArray(store.routines(null, listOf(State.CONFIRMED, State.CANDIDATE)).filter { !isGuest(it.scope) }
            .map { r -> buildJsonObject { put("id", r.id); put("scope", r.scope); put("routine", r.describe()); put("state", r.state.wire); r.stats.forEach { (k, v) -> put(k, v) } } }))
        put("tombstones", JsonArray(store.tombstones().map { t -> buildJsonObject { put("scope", t["scope"] as String); put("signature", t["signature"] as String) } }))
    }

    fun close() = store.close()
}
