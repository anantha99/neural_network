package carmem.car

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Place(
    val id: Int,
    var lat: Double,
    var lon: Double,
    var visits: Int = 0,
    var label: String? = null,
    var nightMinutes: Long = 0,     // dwell between 22:00 and 06:00  -> home
    var workdayMinutes: Long = 0,   // dwell Mon-Fri 09:00-16:00     -> work
    var kind: String? = null,       // "restaurant", "gym": told by navigation or the driver, never guessed
) {
    /** The name routines and episodes use. Stable per place even before it is labelled. */
    val name: String get() = label ?: "place-$id"
}

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * Clusters coordinates into places with a fixed radius and infers home / work
 * labels from dwell time, the way every trip-mining paper does it. Reverse
 * geocoding for display names is the Android module's job and optional.
 *
 * Persist with [snapshot] / [restore]; [CarSession] keeps it in the memory database.
 */
class PlaceResolver(
    private val radiusMeters: Double = 150.0,
    private val homeMinNightMinutes: Long = 6 * 60 * 3,   // three nights
    private val workMinWorkdayMinutes: Long = 5 * 60 * 3, // three workdays
) {
    private val places = mutableListOf<Place>()
    private var nextId = 1

    /** A place's [Place.name] changed at `at`; whoever keeps memories under the old name must move them. */
    var onRenamed: (old: String, new: String, at: LocalDateTime) -> Unit = { _, _, _ -> }

    fun all(): List<Place> = places.toList()

    /** Forget every place. For when the memory itself has been erased. */
    fun clear() { places.clear(); nextId = 1 }

    /** The known place at these coordinates, if any. Unlike [resolve] it never creates one: use it for fixes on the road. */
    fun find(lat: Double, lon: Double): Place? = places.firstOrNull { haversineMeters(it.lat, it.lon, lat, lon) <= radiusMeters }

    fun resolve(lat: Double, lon: Double): Place {
        places.firstOrNull { haversineMeters(it.lat, it.lon, lat, lon) <= radiusMeters }?.let { p ->
            // nudge the centroid toward the new fix
            p.visits += 1
            p.lat += (lat - p.lat) / p.visits; p.lon += (lon - p.lon) / p.visits
            return p
        }
        return Place(nextId++, lat, lon, visits = 1).also { places += it }
    }

    /** Name a place. A name belongs to one place: if another place had it (the driver moved house), that one gives it up. */
    fun label(place: Place, name: String, at: LocalDateTime) {
        if (name.isBlank() || place.label == name) return
        places.firstOrNull { it !== place && it.label == name }?.let { other -> other.label = null; onRenamed(name, other.name, at) }
        val old = place.name
        place.label = name
        onRenamed(old, name, at)
    }

    /** Record a dwell at `place` from `from` to `to` and re-derive home/work labels. */
    fun dwell(place: Place, from: LocalDateTime, to: LocalDateTime) {
        var t = from
        while (t.isBefore(to)) {
            val next = minOf(to, t.plusMinutes(15))
            val mins = java.time.Duration.between(t, next).toMinutes()
            val h = t.hour
            if (h >= 22 || h < 6) place.nightMinutes += mins
            if (t.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && h in 9..15) place.workdayMinutes += mins
            t = next
        }
        inferLabels(to)
    }

    private fun inferLabels(at: LocalDateTime) {
        if (places.none { it.label == "home" }) places.filter { it.label == null && it.nightMinutes >= homeMinNightMinutes }.maxByOrNull { it.nightMinutes }?.let { label(it, "home", at) }
        if (places.none { it.label == "work" }) places.filter { it.label == null && it.workdayMinutes >= workMinWorkdayMinutes }.maxByOrNull { it.workdayMinutes }?.let { label(it, "work", at) }
    }

    fun snapshot(): String = places.joinToString("\n") { "${it.id}\t${it.lat}\t${it.lon}\t${it.visits}\t${it.label ?: ""}\t${it.nightMinutes}\t${it.workdayMinutes}\t${it.kind ?: ""}" }

    fun restore(text: String) {
        places.clear()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val f = line.split("\t")
            places += Place(f[0].toInt(), f[1].toDouble(), f[2].toDouble(), f[3].toInt(), f[4].ifEmpty { null }, f[5].toLong(), f[6].toLong(), f.getOrNull(7)?.ifEmpty { null })
        }
        nextId = (places.maxOfOrNull { it.id } ?: 0) + 1
    }
}
