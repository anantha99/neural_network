package carmem.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import carmem.IdentitySignal
import carmem.car.CarEvent
import carmem.car.OccupantObserved
import java.time.LocalDateTime

/**
 * A connected, previously paired phone is a hard identity signal ("phone", address).
 * Pairing happens once per person through the normal Bluetooth settings; the
 * address is bound to the person with CarMemory.bind("phone", address, personId).
 */
class PhoneSignal(private val context: Context, private val sink: (CarEvent) -> Unit) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (device.bondState == BluetoothDevice.BOND_BONDED)
                sink(OccupantObserved(LocalDateTime.now(), IdentitySignal("phone", device.address)))
        }
    }

    fun start() {
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED), Context.RECEIVER_EXPORTED)
        // phones already connected when the service starts
        val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
        runCatching { adapter?.bondedDevices?.forEach { d -> if (d.isConnectedCompat()) sink(OccupantObserved(LocalDateTime.now(), IdentitySignal("phone", d.address))) } }
    }

    fun stop() = runCatching { context.unregisterReceiver(receiver) }.let { }

    private fun BluetoothDevice.isConnectedCompat(): Boolean =
        runCatching { javaClass.getMethod("isConnected").invoke(this) as Boolean }.getOrDefault(false)
}
