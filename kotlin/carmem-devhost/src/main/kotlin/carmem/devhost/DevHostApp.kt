package carmem.devhost

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import carmem.android.CarMemoryService
import carmem.android.ICarMemory

/** Starts CarMemoryService in the foreground and holds a bind to it for the probes. */
class DevHostApp : Application() {
    @Volatile var memory: ICarMemory? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            memory = ICarMemory.Stub.asInterface(binder)
            Log.i(TAG, "bound to CarMemoryService")
        }
        override fun onServiceDisconnected(name: ComponentName) { memory = null; Log.w(TAG, "CarMemoryService disconnected") }
    }

    override fun onCreate() {
        super.onCreate()
        val service = Intent(this, CarMemoryService::class.java)
        startForegroundService(service)
        bindService(service, connection, Context.BIND_AUTO_CREATE)
    }

    companion object { const val TAG = "carmem-probe" }
}
