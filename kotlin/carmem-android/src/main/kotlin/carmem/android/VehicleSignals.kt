package carmem.android

import android.car.Car
import android.car.CarOccupantZoneManager
import android.car.VehicleIgnitionState
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import carmem.IdentitySignal
import carmem.car.CarEvent
import carmem.car.IgnitionChanged
import carmem.car.OccupantObserved
import carmem.car.SettingChanged
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime

/**
 * Vehicle properties and occupant zones -> CarEvents.
 *
 * Ignition drives the session; HVAC set temperature is the implicit climate
 * preference; the driver zone's Android user is the strongest identity signal.
 * All three properties are privileged on AAOS (see the manifest).
 */
class VehicleSignals(
    private val context: Context,
    private val sink: (CarEvent) -> Unit,
    /** Timestamp source: the service passes the memory's (demo) clock so platform events agree with it. */
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private var car: Car? = null
    private var props: CarPropertyManager? = null
    private var lastIgnitionOn: Boolean? = null

    fun start() {
        car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER) { c, ready ->
            if (!ready) { Log.w(CarMemoryService.TAG, "car service disconnected"); return@createCar }
            props = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            register()
            emitDriverUser(c)
        }
    }

    private fun register() {
        val pm = props ?: return
        pm.registerCallback(callback, VehiclePropertyIds.IGNITION_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        pm.registerCallback(callback, VehiclePropertyIds.HVAC_TEMPERATURE_SET, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        runCatching { pm.registerCallback(callback, VehiclePropertyIds.SEAT_OCCUPANCY, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
            .onFailure { Log.i(CarMemoryService.TAG, "seat occupancy unavailable: ${it.message}") }
    }

    /** The driver seat's Android user is a seat_profile signal; AAOS guest users stay guests in memory. */
    private fun emitDriverUser(c: Car) {
        val zones = c.getCarManager(Car.CAR_OCCUPANT_ZONE_SERVICE) as? CarOccupantZoneManager ?: return
        val driverZone = zones.allOccupantZones.firstOrNull { it.occupantType == CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER } ?: return
        val user = zones.getUserForOccupant(driverZone)
        if (user != CarOccupantZoneManager.INVALID_USER_ID) {
            // isGuestUser() answers for the context's user, so ask through a context for the driver's user.
            // Both calls are system APIs (reflection allowed); isGuestUser(int) is blocked hidden API on targetSdk > 30.
            val isGuest = runCatching {
                val handle = android.os.UserHandle.getUserHandleForUid(user * PER_USER_UID_RANGE)
                val userCtx = Context::class.java.getMethod("createContextAsUser", android.os.UserHandle::class.java, Int::class.javaPrimitiveType)
                    .invoke(context, handle, 0) as Context
                val um = userCtx.getSystemService(android.os.UserManager::class.java)
                android.os.UserManager::class.java.getMethod("isGuestUser").invoke(um) as Boolean
            }.onFailure { Log.w(CarMemoryService.TAG, "guest check failed for user $user; treating as known profile", it) }
                .getOrDefault(false)
            if (!isGuest) sink(OccupantObserved(clock(), IdentitySignal("seat_profile", "user:$user")))
        }
    }

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val now = clock()
            when (value.propertyId) {
                VehiclePropertyIds.IGNITION_STATE -> {
                    val on = (value.value as Int) in setOf(VehicleIgnitionState.ON, VehicleIgnitionState.START)
                    if (on != lastIgnitionOn) { lastIgnitionOn = on; if (on) car?.let(::emitDriverUser); sink(IgnitionChanged(now, on)) }
                }
                VehiclePropertyIds.HVAC_TEMPERATURE_SET -> {
                    // Attribute to the driver's zone only; a passenger's knob is not the driver's preference.
                    if (value.areaId and DRIVER_HVAC_AREAS != 0 || value.areaId == 0)
                        sink(SettingChanged(now, "cabin_temp", JsonPrimitive((value.value as Float).toDouble())))
                }
                VehiclePropertyIds.SEAT_OCCUPANCY -> Unit // reserved: passenger presence for passenger-scoped writes
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) { Log.w(CarMemoryService.TAG, "property error $propertyId/$areaId") }
    }

    fun stop() { props?.unregisterCallback(callback); car?.disconnect() }

    companion object {
        // VehicleAreaSeat.ROW_1_LEFT | ROW_1_CENTER; adjust for right-hand-drive markets via policy if needed.
        const val DRIVER_HVAC_AREAS = 0x0001 or 0x0002
        private const val PER_USER_UID_RANGE = 100_000 // UserHandle.PER_USER_RANGE
    }
}
