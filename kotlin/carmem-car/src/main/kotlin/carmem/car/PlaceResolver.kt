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
 * Persist with [snapshot] / [restore]; the Android service keeps it in the memory db.
 */
class PlaceResolver(
    private val radiusMeters: Double = 150.0,
    private val homeMinNightMinutes: Long = 6 * 60 * 3,   // three nights
    private val workMinWorkdayMinutes: Long = 5 * 60 * 3, // three workdays
) {
    private val places = mutableListOf<Place>()
    private var nextId = 1

    fun all(): List<Place> = places.toList()

    fun resolve(lat: Double, lon: Double): Place {
        places.firstOrNull { haversineMeters(it.lat, it.lon, lat, lon) <= radiusMeters }?.let { p ->
            // nudge the centroid toward the new fix
            p.visits += 1
            p.lat += (lat - p.lat) / p.visits; p.lon += (lon - p.lon) / p.visits
            return p
        }
        return Place(nextId++, lat, lon, visits = 1).also { places += it }
    }

    fun label(place: Place, name: String) { place.label = name }

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
        inferLabels()
    }

    private fun inferLabels() {
        if (places.none { it.label == "home" }) places.filter { it.nightMinutes >= homeMinNightMinutes }.maxByOrNull { it.nightMinutes }?.let { it.label = "home" }
        if (places.none { it.label == "work" }) places.filter { it.label == null && it.workdayMinutes >= workMinWorkdayMinutes }.maxByOrNull { it.workdayMinutes }?.let { it.label = "work" }
    }

    fun snapshot(): String = places.joinToString("\n") { "${it.id}\t${it.lat}\t${it.lon}\t${it.visits}\t${it.label ?: ""}\t${it.nightMinutes}\t${it.workdayMinutes}" }

    fun restore(text: String) {
        places.clear()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val f = line.split("\t")
            places += Place(f[0].toInt(), f[1].toDouble(), f[2].toDouble(), f[3].toInt(), f[4].ifEmpty { null }, f[5].toLong(), f[6].toLong())
        }
        nextId = (places.maxOfOrNull { it.id } ?: 0) + 1
    }
}
