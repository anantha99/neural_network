package carmem.devhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * adb entry point to the ICarMemory surface. Results go to logcat under tag carmem-probe.
 *   op = proactive | recall | hear | context | snapshot | explain | forget | enroll | stats, arg = the op's argument
 *        said | mention (extra kind) | resolve | conversation | state | name | places | destination (extra kind) | export | erase
 *   erase with no arg erases everything; with arg, that person
 *   enroll: arg = person id, extras name and bindings (JSON array of {"kind","value"})
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
                    "stats" -> m.stats()
                    "said" -> { m.said(arg ?: ""); "ok" }
                    "mention" -> { m.mention(intent.getStringExtra("kind") ?: "place", arg ?: ""); "ok" }
                    "resolve" -> m.resolve(arg ?: "")
                    "conversation" -> m.conversation()
                    "state" -> m.state()
                    "name" -> m.namePlace(arg ?: "")
                    "places" -> m.places()
                    "destination" -> { m.setDestination(arg ?: "", intent.getStringExtra("kind"), Double.NaN, Double.NaN); "ok" }
                    "export" -> m.exportMemory(arg)
                    "erase" -> m.erase(arg)
                    "enroll" -> m.enroll(arg ?: "", intent.getStringExtra("name") ?: arg ?: "", intent.getStringExtra("bindings") ?: "[]")
                    else -> "unknown op"
                }
                // logcat truncates long lines; chunk so snapshots arrive whole
                result.chunked(3000).forEachIndexed { i, part -> Log.i(DevHostApp.TAG, "$op[$i]: $part") }
            } catch (t: Throwable) {
                Log.w(DevHostApp.TAG, "$op failed", t)
            }
        }
    }
}
