package carmem.android

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import carmem.CarMemory
import carmem.ContextPacket
import carmem.Policy
import carmem.car.CarEvent
import carmem.car.CarSession
import carmem.car.PlaceResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDateTime

/**
 * The one process that owns the memory database. Both the on-device assistant
 * and the cloud bridge bind here; the memory never opens a network port.
 *
 * All memory work runs on a single handler thread: one writer, no locking in the core.
 */
class CarMemoryService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var memory: CarMemory
    private lateinit var session: CarSession
    private var signals: VehicleSignals? = null
    private var location: LocationFeed? = null
    private var phone: PhoneSignal? = null
    private var lastCloudPacket: ContextPacket? = null
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("carmem").also { it.start() }
        handler = Handler(worker.looper)
        val dir = noBackupFilesDir.also { it.mkdirs() }     // credential-encrypted, per Android user
        val policyFile = File(dir, "policy.json")
        val policy = if (policyFile.exists()) Policy.fromJson(policyFile.readText()) else Policy()
        memory = CarMemory(AndroidSqlDriver(File(dir, "carmem.db").absolutePath), policy)
        val places = PlaceResolver().also { pr -> File(dir, "places.tsv").takeIf { it.exists() }?.let { pr.restore(it.readText()) } }
        session = CarSession(memory, places) { props -> ProposalBus.publish(this, props) }
        handler.post {
            signals = VehicleSignals(this, ::post).also { it.start() }
            location = LocationFeed(this, ::post).also { it.start() }
            phone = PhoneSignal(this, ::post).also { it.start() }
        }
        Log.i(TAG, "carmem service up, db=${File(dir, "carmem.db")}")
    }

    /** Every car event funnels through here onto the worker thread. */
    fun post(e: CarEvent) { handler.post { runCatching { session.onEvent(e) }.onFailure { Log.w(TAG, "event failed: $e", it) } } }

    private fun <T> onWorker(block: () -> T): T {
        if (Thread.currentThread() == worker) return block()
        var result: Result<T>? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post { result = runCatching(block); latch.countDown() }
        latch.await()
        return result!!.getOrThrow()
    }

    private val binder = object : ICarMemory.Stub() {
        override fun hear(text: String, speakerId: String?): String = onWorker { memory.hear(text, speakerId, LocalDateTime.now()).toString() }
        override fun context(query: String, destination: String): String = onWorker {
            val p = memory.context(query, destination, LocalDateTime.now())
            if (destination == "cloud") lastCloudPacket = p
            buildJsonObject { put("rendered", p.render()); put("packet", p.toJson()) }.toString()
        }
        override fun recall(query: String, k: Int): String = onWorker { memory.recall(query, k, LocalDateTime.now()).toString() }
        override fun proactive(location: String?): String = onWorker {
            JsonArray(memory.proactive(LocalDateTime.now(), location).map { p ->
                buildJsonObject { put("id", p.id); put("kind", p.kind); put("record_id", p.recordId); put("scope", p.scope); put("tier", p.tier.wire); put("text", p.text); put("action", JsonObject(p.action)) }
            }).toString()
        }
        override fun handleTool(name: String, argsJson: String): String = onWorker { memory.handleTool(name, json.parseToJsonElement(argsJson) as JsonObject).toString() }
        override fun explain(recordId: String): String = onWorker { memory.explain(recordId).toString() }
        override fun forget(target: String): String = onWorker { memory.forget(target, LocalDateTime.now()).toString() }
        override fun snapshot(forCloud: Boolean): String = onWorker { memory.snapshot(forCloud).toString() }
        override fun unmask(text: String): String = lastCloudPacket?.unmask(text) ?: text
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        handler.post {
            signals?.stop(); location?.stop(); phone?.stop()
            File(noBackupFilesDir, "places.tsv").writeText(session.places.snapshot())
            memory.close()
        }
        worker.quitSafely()
        super.onDestroy()
    }

    companion object { const val TAG = "carmem" }
}

/** Proposals go out as a local broadcast the assistant UI / TTS layer subscribes to. */
object ProposalBus {
    const val ACTION = "carmem.android.PROPOSALS"
    fun publish(ctx: android.content.Context, props: List<carmem.Proposal>) {
        val payload = JsonArray(props.map { p -> buildJsonObject { put("id", p.id); put("kind", p.kind); put("tier", p.tier.wire); put("text", p.text); put("action", JsonObject(p.action)) } })
        ctx.sendBroadcast(Intent(ACTION).setPackage(ctx.packageName).putExtra("proposals", payload.toString()))
    }
}
