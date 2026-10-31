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

/** The driver named the current place ("this is home", "save this as the gym"). */
data class PlaceNamed(override val ts: LocalDateTime, val name: String) : CarEvent
