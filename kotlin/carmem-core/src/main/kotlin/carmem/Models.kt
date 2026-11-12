package carmem

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

const val VEHICLE_SCOPE = "vehicle"
const val GUEST_PREFIX = "guest:"

enum class Tier(val wire: String) { NEVER("never"), SILENT("silent"), SUGGEST("suggest"), ACT("act");
    companion object { fun of(s: String) = entries.first { it.wire == s } }
}

enum class State(val wire: String) { OBSERVED("observed"), CANDIDATE("candidate"), CONFIRMED("confirmed"), RETIRED("retired");
    companion object { fun of(s: String) = entries.first { it.wire == s } }
}

private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

fun iso(ts: LocalDateTime): String = ts.withNano(0).format(ISO)
fun parseIso(s: String): LocalDateTime = LocalDateTime.parse(s.take(19))
fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(12)}"
fun isGuest(scope: String) = scope.startsWith(GUEST_PREFIX)

/** Render a value the way the Python reference does, so rendered text is identical on both sides. */
fun JsonElement.render(): String = when (this) {
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> if (booleanOrNull == true) "True" else "False"
        else -> content
    }
    else -> toString()
}

fun JsonElement.normalized(): String {
    if (this is JsonPrimitive) {
        if (isString) return content.trim().lowercase()
        val d = doubleOrNull
        if (d != null) return if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
    }
    return render().lowercase()
}

fun jsonOf(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}

data class Person(val id: String, val name: String, val roles: List<String> = listOf("driver"))

data class IdentitySignal(val kind: String, val value: String, val confidence: Double = 1.0, val role: String = "driver")

data class Occupancy(
    val driverId: String,
    val passengerIds: List<String> = emptyList(),
    val isGuest: Boolean = false,
    val sessionId: String = newId("ses"),
    val resolvedFrom: List<String> = emptyList(),
) { val scopes: List<String> get() = listOf(driverId) + passengerIds }

data class Trip(
    val start: LocalDateTime,
    val end: LocalDateTime,
    val origin: String,
    val destination: String,
    val occupants: List<String> = emptyList(),
    val settings: Map<String, JsonElement> = emptyMap(),
    val route: Map<String, JsonElement> = emptyMap(),
    val distanceKm: Double = 0.0,
    /** What kind of place the trip ended at ("restaurant", "gym"), when navigation or the driver said. */
    val destinationKind: String? = null,
) {
    fun summary(): String {
        val parts = mutableListOf("trip $origin -> $destination" + (destinationKind?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
        if (route["tolls"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) parts += "via toll road"
        settings.toSortedMap().forEach { (k, v) -> parts += "$k=${v.render()}" }
        return parts.joinToString(" ")
    }
}

data class Episode(val id: Long, val scope: String, val kind: String, val ts: LocalDateTime, val text: String, val payload: JsonElement)

@Serializable
data class Evidence(
    val episode_id: Long,
    val ts: String,
    val value: JsonElement = JsonNull,
    val condition: String? = null,
    val weight: Double = 1.0,
    val note: String = "",
)

data class Fact(
    val id: String,
    val scope: String,
    val category: String,
    val key: String,
    var value: JsonElement,
    val condition: String? = null,
    var state: State = State.OBSERVED,
    val source: String = "implicit",
    val sensitivity: String = "personal",
    var support: MutableList<Evidence> = mutableListOf(),
    var contradictions: MutableList<Evidence> = mutableListOf(),
    var demotion: Int = 0,
    var consecutiveNo: Int = 0,
    val created: String = "",
    var updated: String = "",
    var validFrom: String = "",
    var validUntil: String? = null,
) {
    val isCurrent get() = validUntil == null
    fun describe(): String = "$category/$key = ${value.render()}" + (condition?.let { " (when $it)" } ?: "")
}

data class Routine(
    val id: String,
    val scope: String,
    val signature: String,
    val dow: Int,
    var minuteOfDay: Int,
    val destination: String,
    var origin: String = "",
    val action: Map<String, JsonElement> = emptyMap(),
    var state: State = State.OBSERVED,
    var support: MutableList<Evidence> = mutableListOf(),
    var contradictions: MutableList<Evidence> = mutableListOf(),
    var demotion: Int = 0,
    var consecutiveNo: Int = 0,
    val firstSeen: String = "",
    var lastSeen: String = "",
    var stats: Map<String, JsonElement> = emptyMap(),
) {
    val category get() = "route"
    fun slotText(): String {
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val h = minuteOfDay / 60; val m = minuteOfDay % 60
        return "${days[dow]} %02d:%02d".format(h, m)
    }
    fun describe(): String {
        val frm = if (origin.isNotEmpty()) " from $origin" else ""
        return "${slotText()}$frm -> $destination"
    }
}

data class Proposal(
    val kind: String,
    val recordId: String,
    val scope: String,
    val tier: Tier,
    val text: String,
    val action: Map<String, JsonElement> = emptyMap(),
    val id: String = newId("prop"),
)
