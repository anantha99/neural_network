package carmem

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Every threshold and tier, as data. Mirrors carmem/policy.py field for field. */
@Serializable
data class Policy(
    val routine_window_days: Int = 56,
    val routine_candidate_min_occurrences: Int = 2,
    val routine_confirm_min_occurrences: Int = 3,
    val routine_confirm_min_distinct_weeks: Int = 3,
    val routine_confirm_min_ratio: Double = 0.7,
    val routine_contradiction_window_days: Int = 14,
    val routine_slot_tolerance_min: Int = 45,
    val routine_retire_after_days: Int = 42,
    val guest_routines: Boolean = false,

    val fact_window_days: Int = 56,
    val fact_implicit_min_support: Int = 3,
    val fact_confirm_ratio: Double = 0.7,
    val fact_split_conditions: List<String> = listOf("season", "daypart"),
    val fact_confirm_on_statement: Boolean = true,

    val reconcile_categories: List<String> = listOf("note"),
    val reconcile_top_k: Int = 5,
    val reconcile_noop_similarity: Double = 0.8,
    val reconcile_update_similarity: Double = 0.45,

    val retrieval_recency_half_life_days: Double = 14.0,

    val no_weight: Double = 1.0,
    val demote_after_consecutive_no: Int = 2,
    val tombstone_days: Int = 90,
    val vehicle_min_distinct_persons: Int = 2,
    val episodic_retention_days: Int = 42,

    val hard_signal_priority: List<String> = listOf("seat_profile", "phone", "key_fob"),
    val voice_alone_min_confidence: Double = 1.01,
    /** Unbound hard signals of these kinds enroll a new person when the driver would otherwise be a guest (Kotlin-only). */
    val auto_enroll_kinds: List<String> = listOf("seat_profile"),
    /** Unbound hard signals of these kinds are bound to the recognised driver they appear with (Kotlin-only). */
    val auto_bind_kinds: List<String> = listOf("phone", "key_fob"),

    val tiers: Map<String, String> = mapOf(
        "climate" to "act", "seat" to "act", "media" to "act", "route" to "suggest", "route_prefs" to "silent",
        "contacts" to "never", "health" to "never", "home_address" to "silent", "school" to "silent",
        "note" to "silent", "vehicle_fault" to "silent", "identity" to "silent",
    ),
    val sensitivity: Map<String, String> = mapOf(
        "health" to "sensitive", "contacts" to "sensitive", "home_address" to "sensitive", "school" to "sensitive",
        "finance" to "sensitive", "climate" to "public", "seat" to "public", "media" to "personal", "route" to "personal",
        "route_prefs" to "personal", "note" to "personal", "vehicle_fault" to "public", "identity" to "personal",
    ),
    val mask_in_cloud_categories: List<String> = listOf("health", "contacts", "home_address", "school", "finance"),
    val mask_in_cloud_sensitivity: List<String> = listOf("sensitive"),
) {
    fun tierFor(category: String): Tier = Tier.of(tiers[category] ?: "silent")
    fun sensitivityFor(category: String): String = sensitivity[category] ?: "personal"

    fun effectiveTier(category: String, demotion: Int): Tier {
        val base = tierFor(category)
        if (base == Tier.NEVER) return base
        val idx = maxOf(base.ordinal - maxOf(0, demotion), Tier.SILENT.ordinal)
        return Tier.entries[idx]
    }

    fun maskedInCloud(category: String, sens: String) =
        category in mask_in_cloud_categories || sens in mask_in_cloud_sensitivity

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        fun fromJson(text: String): Policy = json.decodeFromString(text)
        fun toJson(p: Policy): String = json.encodeToString(serializer(), p)
    }
}
