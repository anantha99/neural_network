package carmem.devhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * adb entry point to the ICarMemory surface. Results go to logcat under tag carmem-probe.
 *   op = proactive | recall | hear | context | snapshot | explain | forget | enroll | seed | clock | event | chat | whoami,
 *   arg = the op's argument
 *   enroll: arg = person id, extras name and bindings (JSON array of {"kind","value"})
 *   seed:   replays assets/household_seed7_w8.json (or the asset named by arg) into the memory
 *   clock:  arg = ISO local time to pin ("2026-10-12T07:38:00"), or "reset" / empty for the wall clock
 *   event:  arg = CarEvent JSON, e.g. {"type":"occupant","kind":"key_fob","value":"fob-asha"} (see ICarMemory.injectEvent)
 *   chat:   arg = what the driver says, extra dest = local (default) | cloud
 *   whoami: the current driver
 */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DevHostApp
        val op = intent.getStringExtra("op") ?: "proactive"
        val arg = intent.getStringExtra("arg")
        // ICarMemory calls block on the service's worker thread, behind any model call (tens of seconds on the
        // cloud model). Don't hold the broadcast for that, or queued probes ANR; the foreground service keeps
        // the process alive while the thread runs.
        thread(name = "carmem-probe") {
            try {
                val m = app.memory ?: run { Log.w(DevHostApp.TAG, "$op: service not bound yet"); return@thread }
                val result = when (op) {
                    "proactive" -> m.proactive(arg)
                    "recall" -> m.recall(arg ?: "", intent.getIntExtra("k", 10))
                    "hear" -> m.hear(arg ?: "", intent.getStringExtra("speaker"))
                    "context" -> m.context(arg ?: "", intent.getStringExtra("dest") ?: "local")
                    "snapshot" -> m.snapshot(false)
                    "explain" -> m.explain(arg ?: "")
                    "forget" -> m.forget(arg ?: "")
                    "enroll" -> m.enroll(arg ?: "", intent.getStringExtra("name") ?: arg ?: "", intent.getStringExtra("bindings") ?: "[]")
                    "seed" -> m.seed(context.assets.open(arg?.takeIf { it.isNotBlank() } ?: FIXTURE_ASSET).bufferedReader().use { it.readText() })
                    "clock" -> m.setClock(if (arg == null || arg == "reset") "" else arg)
                    "event" -> m.injectEvent(arg ?: "{}")
                    "chat" -> m.chat(arg ?: "", intent.getStringExtra("dest") ?: "local")
                    "whoami" -> m.whoami()
                    else -> "unknown op"
                }
                // logcat truncates long lines; chunk so snapshots arrive whole
                result.chunked(3000).forEachIndexed { i, part -> Log.i(DevHostApp.TAG, "$op[$i]: $part") }
            } catch (t: Throwable) {
                Log.w(DevHostApp.TAG, "$op failed", t)
            }
        }
    }

    companion object {
        /** fixtures/household_seed7_w8.json, packaged as an asset by build.gradle.kts (assets srcDir ../../fixtures). */
        const val FIXTURE_ASSET = "household_seed7_w8.json"
    }
}
