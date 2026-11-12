package carmem

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.roundToInt

fun seasonOf(ts: LocalDateTime) = when (ts.monthValue) { 12, 1, 2 -> "winter"; 3, 4, 5 -> "spring"; 6, 7, 8 -> "summer"; else -> "autumn" }
fun daypartOf(ts: LocalDateTime) = when (ts.hour) { in 5..10 -> "morning"; in 11..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }
fun conditionsFor(ts: LocalDateTime) = mapOf("daypart" to daypartOf(ts), "season" to seasonOf(ts))
fun condStr(c: Map<String, String>) = c.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
fun condGet(cond: String?, name: String): String? = cond?.split(";")?.firstNotNullOfOrNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 && it[0] == name }?.get(1) }
fun condMatches(cond: String, now: Map<String, String>) = cond.split(";").all { p -> p.split("=", limit = 2).let { it.size != 2 || now[it[0]] == it[1] } }
fun normPlace(p: String) = p.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
fun routineSignature(dow: Int, dest: String) = "$dow:${normPlace(dest)}"
fun routineTombstoneKey(dest: String) = "route:${normPlace(dest)}"
fun minuteOf(ts: LocalDateTime) = ts.hour * 60 + ts.minute

/** Number of calendar days with weekday `dow` (0=Mon) in [start.date, end.date]. */
fun countWeekday(start: LocalDateTime, end: LocalDateTime, dow: Int): Int {
    if (end.isBefore(start)) return 0
    val first = start.toLocalDate()
    val ahead = ((dow - (first.dayOfWeek.value - 1)) % 7 + 7) % 7
    val firstHit = first.plusDays(ahead.toLong())
    if (firstHit.isAfter(end.toLocalDate())) return 0
    return (ChronoUnit.DAYS.between(firstHit, end.toLocalDate()) / 7).toInt() + 1
}

private fun days(n: Int): Long = n.toLong()

/** Evidence the state machine can no longer use: older than `cutoff` and not among the last [KEEP_EVIDENCE] kept for explain(). */
private const val KEEP_EVIDENCE = 10
internal fun trimEvidence(list: MutableList<Evidence>, cutoff: LocalDateTime): MutableList<Evidence> {
    if (list.size <= KEEP_EVIDENCE) return list
    val c = iso(cutoff); val keepFrom = list.size - KEEP_EVIDENCE
    return list.filterIndexedTo(mutableListOf()) { i, e -> i >= keepFrom || e.ts.take(19) >= c }
}
private fun jnum(n: Number): JsonElement = JsonPrimitive(n)
private fun jstr(s: String?): JsonElement = s?.let { JsonPrimitive(it) } ?: JsonNull

class Consolidator(val store: Store, val policy: Policy, val embedder: Embedder = HashingEmbedder(), llm: LlmClient? = null) {
    private val reconciler = Reconciler(policy, embedder, llm)

    // ------------------------------------------------------------ stated facts
    fun stateFact(scope: String, c: Candidate, ep: Episode, now: LocalDateTime): Fact {
        val sens = policy.sensitivityFor(c.category)
        val ev = Evidence(ep.id, iso(ep.ts), c.value, c.condition, note = "stated")
        if (c.category in policy.reconcile_categories) return stateFreeText(scope, c, ev, now)
        val existing = store.facts(listOf(scope), c.category, c.key, listOf(State.CONFIRMED, State.CANDIDATE))
            .filter { it.source == "stated" && it.condition == c.condition && it.isCurrent }
        for (old in existing) {
            if (old.value.normalized() == c.value.normalized()) { old.support += ev; old.updated = iso(now); store.upsertFact(old); return old }
            invalidate(old, ev, now, "superseded")
        }
        val f = newStated(scope, c.category, c.key, c.value, c.condition, sens, ev, now)
        if (c.about == "vehicle") maybePromoteVehicle(c.category, c.key, now)
        return f
    }

    private fun stateFreeText(scope: String, c: Candidate, ev: Evidence, now: LocalDateTime): Fact {
        val existing = store.facts(listOf(scope), c.category, null, listOf(State.CONFIRMED)).filter { it.isCurrent }
        val d = reconciler.decide(c.value.render(), existing, c.key)
        store.audit(now, scope, "fact.reconcile", buildJsonObject { put("op", d.op); put("by", d.by); put("similarity", d.similarity) })
        return when {
            d.op == "noop" && d.target != null -> { d.target.support += ev; d.target.updated = iso(now); store.upsertFact(d.target); d.target }
            d.op == "update" && d.target != null -> { invalidate(d.target, ev, now, "updated"); newStated(scope, c.category, d.target.key, c.value, c.condition, policy.sensitivityFor(c.category), ev, now, d.target) }
            else -> newStated(scope, c.category, c.key, c.value, c.condition, policy.sensitivityFor(c.category), ev, now)
        }
    }

    private fun invalidate(old: Fact, ev: Evidence, now: LocalDateTime, why: String) {
        old.state = State.RETIRED; old.validUntil = ev.ts; old.contradictions += ev; old.updated = iso(now)
        store.upsertFact(old)
        store.audit(now, old.scope, "fact.$why", buildJsonObject { put("old", old.id); put("valid_until", ev.ts) })
    }

    private fun newStated(scope: String, category: String, key: String, value: JsonElement, condition: String?, sens: String, ev: Evidence, now: LocalDateTime, predecessor: Fact? = null): Fact {
        val f = Fact(newId("fact"), scope, category, key, value, condition,
            if (policy.fact_confirm_on_statement) State.CONFIRMED else State.CANDIDATE, "stated", sens,
            mutableListOf(ev), created = iso(now), updated = iso(now), validFrom = ev.ts)
        if (predecessor != null) f.support = (predecessor.support.takeLast(5).map { it.copy(note = "carried from ${predecessor.id}") } + ev).toMutableList()
        store.upsertFact(f)
        store.audit(now, scope, "fact.stated", buildJsonObject { put("id", f.id); put("fact", f.describe()) })
        return f
    }

    // ------------------------------------------------------------ implicit facts
    fun observeSetting(scope: String, category: String, key: String, value: JsonElement, ep: Episode, now: LocalDateTime): Fact {
        val conds = conditionsFor(ep.ts)
        val base = implicitBase(scope, category, key) ?: Fact(newId("fact"), scope, category, key, value, null, State.OBSERVED, "implicit",
            policy.sensitivityFor(category), created = iso(now), updated = iso(now), validFrom = iso(ep.ts))
        base.support += Evidence(ep.id, iso(ep.ts), value, condStr(conds), note = "observed")
        base.updated = iso(now)
        evaluateImplicit(base, now)
        return base
    }

    private fun implicitBase(scope: String, category: String, key: String): Fact? =
        store.facts(listOf(scope), category, key).firstOrNull { it.source == "implicit" && it.condition == null && it.state != State.RETIRED }

    private fun evaluateImplicit(base: Fact, now: LocalDateTime) {
        val window = now.minusDays(days(policy.fact_window_days))
        // One observation per trip, forever, would make every read of this fact slower each week. A day of slack for clock changes.
        base.support = trimEvidence(base.support, window.minusDays(1))
        val recent = base.support.filter { !parseIso(it.ts).isBefore(window) }
        val counts = recent.groupingBy { it.value.normalized() }.eachCount()
        val n = recent.size
        if (n == 0) { store.upsertFact(base); return }
        val (dominant, dn) = counts.maxByOrNull { it.value }!!.let { it.key to it.value }
        val ratio = dn.toDouble() / n
        if (n >= policy.fact_implicit_min_support && ratio >= policy.fact_confirm_ratio) {
            val changed = base.state != State.CONFIRMED || base.value.normalized() != dominant
            base.value = denorm(dominant, recent); base.state = State.CONFIRMED
            store.upsertFact(base)
            if (changed) store.audit(now, base.scope, "fact.confirmed", buildJsonObject { put("id", base.id); put("fact", base.describe()); put("n", n) })
            return
        }
        if (n >= policy.fact_implicit_min_support && trySplit(base, recent, now)) return
        base.value = denorm(dominant, recent)
        base.state = if (n >= 2) State.CANDIDATE else State.OBSERVED
        store.upsertFact(base)
    }

    private fun trySplit(base: Fact, recent: List<Evidence>, now: LocalDateTime): Boolean {
        for (condName in policy.fact_split_conditions) {
            val groups = linkedMapOf<String, MutableList<Evidence>>()
            for (e in recent) condGet(e.condition, condName)?.let { groups.getOrPut("$condName=$it") { mutableListOf() } += e }
            if (groups.size < 2) continue
            val resolved = linkedMapOf<String, JsonElement>(); var ok = true
            for ((g, evs) in groups) {
                val c = evs.groupingBy { it.value.normalized() }.eachCount(); val (dom, dn) = c.maxByOrNull { it.value }!!.let { it.key to it.value }
                if (evs.size < policy.fact_implicit_min_support || dn.toDouble() / evs.size < policy.fact_confirm_ratio) { ok = false; break }
                resolved[g] = denorm(dom, evs)
            }
            if (!ok || resolved.values.map { it.normalized() }.toSet().size < 2) continue
            for ((g, evs) in groups) {
                val existing = store.facts(listOf(base.scope), base.category, base.key).firstOrNull { it.source == "implicit" && it.condition == g && it.state != State.RETIRED }
                val f = existing ?: Fact(newId("fact"), base.scope, base.category, base.key, resolved[g]!!, g, State.CONFIRMED, "implicit", base.sensitivity,
                    created = iso(now), updated = iso(now), validFrom = evs.minOf { it.ts })
                f.value = resolved[g]!!; f.state = State.CONFIRMED; f.support = evs.toMutableList(); f.updated = iso(now)
                store.upsertFact(f)
            }
            base.state = State.CANDIDATE; base.updated = iso(now); store.upsertFact(base)
            store.audit(now, base.scope, "fact.split", buildJsonObject { put("id", base.id); put("by", condName) })
            return true
        }
        return false
    }

    // ------------------------------------------------------------ vehicle
    fun sensorFact(category: String, key: String, value: JsonElement, ep: Episode, now: LocalDateTime): Fact {
        val ev = Evidence(ep.id, iso(ep.ts), value, note = "sensor")
        store.facts(listOf(VEHICLE_SCOPE), category, key).firstOrNull { it.state != State.RETIRED }?.let {
            it.value = value; it.state = State.CONFIRMED; it.updated = iso(now); it.support += ev
            it.support = trimEvidence(it.support, now.minusDays(days(policy.fact_window_days) + 1)); store.upsertFact(it); return it
        }
        val f = Fact(newId("fact"), VEHICLE_SCOPE, category, key, value, null, State.CONFIRMED, "sensor", "public", mutableListOf(ev), created = iso(now), updated = iso(now), validFrom = iso(ep.ts))
        store.upsertFact(f)
        store.audit(now, VEHICLE_SCOPE, "vehicle.fact", buildJsonObject { put("id", f.id); put("fact", f.describe()) })
        return f
    }

    private fun maybePromoteVehicle(category: String, key: String, now: LocalDateTime): Fact? {
        val claims = store.facts(null, category, key, listOf(State.CONFIRMED)).filter { it.scope != VEHICLE_SCOPE && !isGuest(it.scope) }
        val persons = claims.map { it.scope }.toSet()
        if (persons.size < policy.vehicle_min_distinct_persons) return null
        if (store.facts(listOf(VEHICLE_SCOPE), category, key).any { it.state == State.CONFIRMED }) return null
        val latest = claims.maxByOrNull { it.updated }!!
        val support = claims.flatMap { it.support }
        val f = Fact(newId("fact"), VEHICLE_SCOPE, category, key, latest.value, null, State.CONFIRMED, "stated", "public", support.toMutableList(),
            created = iso(now), updated = iso(now), validFrom = support.minOf { it.ts })
        store.upsertFact(f)
        store.audit(now, VEHICLE_SCOPE, "vehicle.promoted", buildJsonObject { put("id", f.id) })
        return f
    }

    // ------------------------------------------------------------ routines
    fun observeTrip(scope: String, trip: Trip, ep: Episode, now: LocalDateTime): Routine? {
        if (isGuest(scope) && !policy.guest_routines) return null
        val dow = trip.start.dayOfWeek.value - 1
        val mod = minuteOf(trip.start)
        val sig = routineSignature(dow, trip.destination)
        if (store.isTombstoned(scope, routineTombstoneKey(trip.destination), now)) {
            store.audit(now, scope, "routine.blocked_by_tombstone", buildJsonObject { put("signature", sig) })
            contradictSlot(scope, trip, ep, now, null)
            return null
        }
        val tol = policy.routine_slot_tolerance_min
        val rt = store.routines(listOf(scope), signature = sig).firstOrNull { it.state != State.RETIRED && abs(it.minuteOfDay - mod) <= tol }
            ?: Routine(newId("rt"), scope, sig, dow, mod, trip.destination, trip.origin,
                mapOf("type" to JsonPrimitive("navigate"), "destination" to JsonPrimitive(trip.destination)), firstSeen = iso(trip.start), lastSeen = iso(trip.start))
        rt.support += Evidence(ep.id, iso(trip.start), JsonPrimitive(trip.destination), trip.origin, note = "trip")
        rt.lastSeen = iso(trip.start)
        rt.consecutiveNo = 0
        val mins = rt.support.takeLast(10).map { minuteOf(parseIso(it.ts)) }
        rt.minuteOfDay = (mins.sum().toDouble() / mins.size).roundToInt()
        val origins = rt.support.takeLast(10).mapNotNull { it.condition }.filter { it.isNotEmpty() }.groupingBy { normPlace(it) }.eachCount()
        origins.maxByOrNull { it.value }?.key?.let { top -> rt.origin = rt.support.asReversed().firstOrNull { normPlace(it.condition ?: "") == top }?.condition ?: rt.origin }
        val today = trip.start.toLocalDate()
        rt.contradictions = rt.contradictions.filterNot { it.note == "went elsewhere" && parseIso(it.ts).toLocalDate() == today }.toMutableList()
        evaluateRoutine(rt, now)
        contradictSlot(scope, trip, ep, now, rt.id)
        return rt
    }

    private fun contradictSlot(scope: String, trip: Trip, ep: Episode, now: LocalDateTime, exclude: String?) {
        val mod = minuteOf(trip.start); val tol = policy.routine_slot_tolerance_min; val today = trip.start.toLocalDate()
        for (r in store.routines(listOf(scope), listOf(State.CANDIDATE, State.CONFIRMED), dow = trip.start.dayOfWeek.value - 1)) {
            if (r.id == exclude || abs(r.minuteOfDay - mod) > tol) continue
            if (normPlace(r.destination) == normPlace(trip.destination)) continue
            if (r.origin.isNotEmpty() && trip.origin.isNotEmpty() && normPlace(r.origin) != normPlace(trip.origin)) continue
            if (r.support.any { it.note == "trip" && parseIso(it.ts).toLocalDate() == today }) continue
            r.contradictions += Evidence(ep.id, iso(trip.start), JsonPrimitive(trip.destination), note = "went elsewhere")
            evaluateRoutine(r, now)
        }
    }

    fun evaluateRoutine(rt: Routine, now: LocalDateTime): Routine {
        val p = policy
        val window = now.minusDays(days(p.routine_window_days))
        rt.support = trimEvidence(rt.support, window.minusDays(1)); rt.contradictions = trimEvidence(rt.contradictions, window.minusDays(1))
        val sup = rt.support.map { parseIso(it.ts) }.filter { !it.isBefore(window) }
        val occ = sup.size
        val weeks = sup.map { it.get(IsoFields.WEEK_BASED_YEAR) to it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }.toSet().size
        val eligible = if (sup.isEmpty()) 0 else countWeekday(sup.min(), now, rt.dow)
        val ratio = if (eligible > 0) occ.toDouble() / eligible else 0.0
        val recentContra = rt.contradictions.filter { !parseIso(it.ts).isBefore(now.minusDays(days(p.routine_contradiction_window_days))) }
        val prev = rt.state
        val lastSeen = rt.lastSeen.takeIf { it.isNotEmpty() }?.let(::parseIso)
        rt.state = when {
            lastSeen != null && ChronoUnit.SECONDS.between(lastSeen, now) > p.routine_retire_after_days * 86400L -> State.RETIRED
            occ >= p.routine_confirm_min_occurrences && weeks >= p.routine_confirm_min_distinct_weeks && ratio >= p.routine_confirm_min_ratio && recentContra.isEmpty() -> State.CONFIRMED
            occ >= p.routine_candidate_min_occurrences -> State.CANDIDATE
            else -> State.OBSERVED
        }
        rt.stats = mapOf("occurrences" to jnum(occ), "distinct_weeks" to jnum(weeks), "eligible_slots" to jnum(eligible),
            "ratio" to jnum(Math.round(ratio * 1000) / 1000.0), "recent_contradictions" to jnum(recentContra.size))
        store.upsertRoutine(rt)
        if (rt.state != prev) store.audit(now, rt.scope, "routine.state", buildJsonObject { put("id", rt.id); put("from", prev.wire); put("to", rt.state.wire); put("routine", rt.describe()) })
        return rt
    }

    // ------------------------------------------------------------ proposals
    fun proposals(scope: String, now: LocalDateTime, suppressed: Set<String>, location: String? = null, moving: Boolean = false): List<Proposal> {
        val out = mutableListOf<Proposal>()
        if (isGuest(scope)) return out
        val tol = policy.routine_slot_tolerance_min; val mod = minuteOf(now)
        for (r in store.routines(listOf(scope), listOf(State.CONFIRMED), dow = now.dayOfWeek.value - 1)) {
            if (r.id in suppressed || abs(r.minuteOfDay - mod) > tol) continue
            if (location != null && r.origin.isNotEmpty() && normPlace(location) != normPlace(r.origin)) continue
            if (location != null && normPlace(location) == normPlace(r.destination)) continue
            val tier = policy.effectiveTier(r.category, r.demotion)
            if (tier == Tier.NEVER || tier == Tier.SILENT) continue
            val text = if (tier == Tier.SUGGEST) "Heading to ${r.destination}? I've queued the route; say no to skip." else "Navigating to ${r.destination}."
            out += Proposal("routine", r.id, scope, tier, text, r.action)
        }
        val conds = conditionsFor(now)
        val confirmed = store.facts(listOf(scope), states = listOf(State.CONFIRMED))
        for (f in confirmed) {
            // only settings the car can apply; a stated like or dislike in these categories is context, not an action
            if (f.id in suppressed || SETTING_CATEGORIES[f.key] != f.category) continue
            if (f.condition != null && !condMatches(f.condition, conds)) continue
            val tier = policy.effectiveTier(f.category, f.demotion)
            if (tier == Tier.NEVER || tier == Tier.SILENT) continue
            if (moving && f.category in policy.act_blocked_while_moving) continue
            if (f.condition == null && confirmed.any { g -> g.category == f.category && g.key == f.key && g.condition != null && condMatches(g.condition, conds) }) continue
            out += Proposal("fact", f.id, scope, tier, "Setting ${f.key.replace('_', ' ')} to ${f.value.render()}.",
                mapOf("type" to JsonPrimitive("set"), "key" to JsonPrimitive(f.key), "value" to f.value))
        }
        return out
    }

    // ------------------------------------------------------------ correction
    fun reject(prop: Proposal, ep: Episode, now: LocalDateTime): JsonObject {
        val rt = if (prop.kind == "routine") store.getRoutine(prop.recordId) else null
        val f = if (prop.kind == "fact") store.getFact(prop.recordId) else null
        if (rt == null && f == null) return buildJsonObject { put("applied", false) }
        val ev = Evidence(ep.id, iso(ep.ts), JsonNull, weight = policy.no_weight, note = "said no")
        var demoted = false
        val category: String; val demotion: Int
        if (rt != null) {
            rt.consecutiveNo += 1; rt.contradictions += ev
            if (rt.consecutiveNo >= policy.demote_after_consecutive_no) { rt.demotion += 1; rt.consecutiveNo = 0; demoted = true }
            evaluateRoutine(rt, now); category = rt.category; demotion = rt.demotion
        } else {
            f!!.consecutiveNo += 1; f.contradictions += ev
            if (f.consecutiveNo >= policy.demote_after_consecutive_no) { f.demotion += 1; f.consecutiveNo = 0; demoted = true }
            f.updated = iso(now); store.upsertFact(f); category = f.category; demotion = f.demotion
        }
        val tier = policy.effectiveTier(category, demotion)
        store.audit(now, prop.scope, "correction.no", buildJsonObject { put("id", prop.recordId); put("demoted", demoted); put("tier", tier.wire) })
        return buildJsonObject { put("applied", true); put("record", prop.recordId); put("demoted", demoted); put("tier", tier.wire) }
    }

    fun accept(prop: Proposal, ep: Episode, now: LocalDateTime): JsonObject {
        val ev = Evidence(ep.id, iso(ep.ts), JsonNull, note = "accepted")
        if (prop.kind == "routine") store.getRoutine(prop.recordId)?.let { it.consecutiveNo = 0; it.support += ev; evaluateRoutine(it, now) } ?: return buildJsonObject { put("applied", false) }
        else store.getFact(prop.recordId)?.let { it.consecutiveNo = 0; it.support += ev; it.updated = iso(now); store.upsertFact(it) } ?: return buildJsonObject { put("applied", false) }
        return buildJsonObject { put("applied", true); put("record", prop.recordId) }
    }

    fun forget(recordId: String, now: LocalDateTime, reason: String = "driver asked"): JsonObject {
        val expires = now.plusDays(days(policy.tombstone_days))
        store.getRoutine(recordId)?.let { rt ->
            val removed = store.routines(listOf(rt.scope)).filter { normPlace(it.destination) == normPlace(rt.destination) }.map { store.deleteRoutine(it.id); it.id }
            val key = routineTombstoneKey(rt.destination)
            store.addTombstone(rt.scope, key, expires, reason)
            store.audit(now, rt.scope, "forget.routine", buildJsonObject { put("tombstone", key) })
            return buildJsonObject { put("forgot", "routine"); put("id", rt.id); put("removed", removed.size); put("description", rt.describe()); put("tombstone_until", iso(expires)) }
        }
        store.getFact(recordId)?.let { f ->
            store.deleteFact(f.id)
            store.addTombstone(f.scope, "${f.category}/${f.key}", expires, reason)
            store.audit(now, f.scope, "forget.fact", buildJsonObject { put("id", f.id); put("fact", f.describe()) })
            return buildJsonObject { put("forgot", "fact"); put("id", f.id); put("description", f.describe()); put("tombstone_until", iso(expires)) }
        }
        return buildJsonObject { put("forgot", JsonNull) }
    }

    fun factTombstoned(scope: String, category: String, key: String, now: LocalDateTime) = store.isTombstoned(scope, "$category/$key", now)

    // ------------------------------------------------------------ maintenance
    fun maintenance(now: LocalDateTime): Map<String, Int> {
        val removed = store.deleteEpisodesBefore(now.minusDays(days(policy.episodic_retention_days)))
        val expired = store.purgeExpiredTombstones(now)
        var retired = 0
        for (r in store.routines(null, listOf(State.CANDIDATE, State.CONFIRMED, State.OBSERVED))) {
            val before = r.state; evaluateRoutine(r, now); if (before != State.RETIRED && r.state == State.RETIRED) retired++
        }
        return mapOf("episodes_removed" to removed, "tombstones_expired" to expired, "routines_retired" to retired)
    }

    private fun denorm(norm: String, evs: List<Evidence>): JsonElement = evs.firstOrNull { it.value.normalized() == norm }?.value ?: JsonPrimitive(norm)
}
