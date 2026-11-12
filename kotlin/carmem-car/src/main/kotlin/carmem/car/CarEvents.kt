package carmem.car

import carmem.IdentitySignal
import kotlinx.serialization.json.JsonElement
import java.time.LocalDateTime

/**
 * What the car tells the memory. The Android module turns platform callbacks
 * (vehicle properties, occupant zones, Bluetooth, location, speech) into these;
 * everything downstream is platform-free and unit-tested on the JVM.
 */
sealed interface CarEvent { val ts: LocalDateTime }

data class IgnitionChanged(override val ts: LocalDateTime, val on: Boolean) : CarEvent

data class LocationFix(override val ts: LocalDateTime, val lat: Double, val lon: Double, val speedMps: Double? = null) : CarEvent

/** A cabin setting the occupant changed: cabin_temp, seat_position, media, fan. */
data class SettingChanged(override val ts: LocalDateTime, val key: String, val value: JsonElement) : CarEvent

/** An identity observation: seat profile / paired phone / key fob (hard) or voice (soft). */
data class OccupantObserved(override val ts: LocalDateTime, val signal: IdentitySignal) : CarEvent

data class UtteranceHeard(override val ts: LocalDateTime, val text: String, val speakerId: String? = null) : CarEvent

data class VehicleFault(override val ts: LocalDateTime, val key: String, val value: JsonElement) : CarEvent

/** A seat became occupied or empty. `driver` marks the driver's seat; the others are passengers. */
data class SeatOccupied(override val ts: LocalDateTime, val seat: String, val occupied: Boolean, val driver: Boolean = false) : CarEvent

/**
 * Navigation was started to somewhere with a name, and perhaps a kind ("restaurant") and coordinates. When the car
 * arrives, the place takes the name and kind, which is how "the restaurant from last Friday" can be answered later.
 */
data class DestinationSet(override val ts: LocalDateTime, val name: String, val kind: String? = null, val lat: Double? = null, val lon: Double? = null) : CarEvent

/** The driver named the current place ("this is home", "save this as the gym"). */
data class PlaceNamed(override val ts: LocalDateTime, val name: String) : CarEvent
