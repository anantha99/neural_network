package carmem

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime

/** Signals that identify a person on their own once bound. */
val HARD_SIGNAL_KINDS = setOf("seat_profile", "phone", "key_fob")
private val HARD = HARD_SIGNAL_KINDS
private val SOFT = setOf("voice")

class IdentityResolver(private val store: Store, private val policy: Policy) {
    fun enroll(person: Person, bindings: List<Pair<String, String>> = emptyList()) {
        store.addPerson(person); bindings.forEach { (k, v) -> store.bindIdentity(k, v, person.id) }
    }

    fun resolve(signals: List<IdentitySignal>, now: LocalDateTime): Occupancy {
        val (driver0, used0) = resolveOne(signals.filter { it.role == "driver" })
        val used = used0.toMutableList()
        val isGuest = driver0 == null
        val driver = driver0 ?: (GUEST_PREFIX + newId("g").substring(2)).also { used += "guest_default" }
        val passengers = mutableListOf<String>()
        for (s in signals.filter { it.role == "passenger" }) {
            val (pid, _) = resolveOne(listOf(s))
            if (pid != null && pid != driver && pid !in passengers) passengers += pid
        }
        store.audit(now, driver, "identity.resolved", buildJsonObject { put("guest", isGuest) })
        return Occupancy(driver, passengers, isGuest, resolvedFrom = used)
    }

    /** Who the driver signals resolve to, without auditing or opening anything; null means a guest. */
    fun peekDriver(signals: List<IdentitySignal>): String? = resolveOne(signals.filter { it.role == "driver" }).first

    /** Distinct enrolled persons any of the driver signals is bound to (hard kinds and voice). */
    fun boundDrivers(signals: List<IdentitySignal>): Set<String> =
        signals.filter { it.role == "driver" && (it.kind in HARD || it.kind in SOFT) }.mapNotNull { store.lookupIdentity(it.kind, it.value) }.toSet()

    private fun resolveOne(signals: List<IdentitySignal>): Pair<String?, List<String>> {
        val hard = linkedMapOf<String, String>()
        for (kind in policy.hard_signal_priority) for (s in signals) if (s.kind == kind && kind in HARD) store.lookupIdentity(kind, s.value)?.let { hard[kind] = it }
        val voice = signals.filter { it.kind in SOFT }.sortedByDescending { it.confidence }
        if (hard.isNotEmpty()) {
            val candidates = hard.values.distinct()
            if (candidates.size == 1) return candidates[0] to hard.keys.toList()
            for (v in voice) { val vid = store.lookupIdentity("voice", v.value); if (vid in candidates) return vid to hard.keys.toList() + "voice" }
            for (kind in policy.hard_signal_priority) if (kind in hard) return hard[kind] to listOf(kind)
        }
        for (v in voice) if (v.confidence >= policy.voice_alone_min_confidence) store.lookupIdentity("voice", v.value)?.let { return it to listOf("voice") }
        return null to emptyList()
    }
}
