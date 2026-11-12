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
    private var llm: carmem.LlmClient? = null
    private lateinit var session: CarSession
    private var signals: VehicleSignals? = null
    private var location: LocationFeed? = null
    private var phone: PhoneSignal? = null
    private var lastCloudPacket: ContextPacket? = null
    private val latency = carmem.LatencyRecorder()
    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("carmem").also { it.start() }
        handler = Handler(worker.looper)
        val dir = noBackupFilesDir.also { it.mkdirs() }     // credential-encrypted, per Android user
        val policyFile = File(dir, "policy.json")
        val policy = if (policyFile.exists()) Policy.fromJson(policyFile.readText()) else Policy()
        // On-device model if one has been provisioned, else the dev-only cloud Gemma; without either extraction stays rule-based.
        llm = File(dir, MODEL_FILE).takeIf { it.exists() }?.let { LiteRtLmClient(it.absolutePath, cacheDir.absolutePath) }
            ?: File(dir, GeminiLlmClient.KEY_FILE).takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }?.let { GeminiLlmClient(it[0], it.getOrElse(1) { GeminiLlmClient.DEFAULT_MODEL }) }
        memory = CarMemory(AndroidSqlDriver(File(dir, "carmem.db").absolutePath), policy, llm)
        // Places live in the memory database (CarSession restores and saves them). places.tsv is what builds before
        // that wrote at shutdown; it is read once, and the database copy wins from the first save on.
        val places = PlaceResolver().also { pr -> File(dir, "places.tsv").takeIf { it.exists() }?.let { pr.restore(it.readText()) } }
        session = CarSession(memory, places) { props -> ProposalBus.publish(this, props) }
        handler.post {
            signals = VehicleSignals(this, ::post).also { it.start() }
            location = LocationFeed(this, ::post).also { it.start() }
            phone = PhoneSignal(this, ::post).also { it.start() }
        }
        Log.i(TAG, "carmem service up, db=${File(dir, "carmem.db")}, llm=${llm?.javaClass?.simpleName ?: "rules"}")
    }

    /** Every car event funnels through here onto the worker thread. */
    fun post(e: CarEvent) { Log.d(TAG, "event $e"); handler.post { runCatching { session.onEvent(e) }.onFailure { Log.w(TAG, "event failed: $e", it) } } }

    /** Run a client's call on the worker thread and time it: what the client waited, and how much of that was the queue. */
    private fun <T> onWorker(name: String, block: () -> T): T {
        val asked = latency.now()
        var started = asked
        try {
            if (Thread.currentThread() == worker) return block()
            var result: Result<T>? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post { started = latency.now(); result = runCatching(block); latch.countDown() }
            latch.await()
            return result!!.getOrThrow()
        } finally {
            latency.record(name, asked, started)
            val ms = (latency.now() - asked) / 1e6
            if (ms > latency.budgetMs) Log.w(TAG, "$name took ${ms.toInt()} ms (queued ${((started - asked) / 1e6).toInt()} ms)")
        }
    }

    private val binder = object : ICarMemory.Stub() {
        override fun hear(text: String, speakerId: String?): String = onWorker("hear") { memory.hear(text, speakerId, LocalDateTime.now()).toString() }
        override fun context(query: String, destination: String): String = onWorker("context") {
            val p = memory.context(query, destination, LocalDateTime.now())
            if (destination == "cloud") lastCloudPacket = p
            buildJsonObject { put("rendered", p.render()); put("packet", p.toJson()) }.toString()
        }
        override fun recall(query: String, k: Int): String = onWorker("recall") { memory.recall(query, k, LocalDateTime.now()).toString() }
        override fun proactive(location: String?): String = onWorker("proactive") {
            // A caller that does not know where the car is gets the car's own answer; motion comes from the session either way.
            JsonArray(memory.proactive(LocalDateTime.now(), location ?: session.location).map { p ->
                buildJsonObject { put("id", p.id); put("kind", p.kind); put("record_id", p.recordId); put("scope", p.scope); put("tier", p.tier.wire); put("text", p.text); put("action", JsonObject(p.action)) }
            }).toString()
        }
        override fun handleTool(name: String, argsJson: String): String = onWorker("tool:$name") { memory.handleTool(name, json.parseToJsonElement(argsJson) as JsonObject).toString() }
        override fun explain(recordId: String): String = onWorker("explain") { memory.explain(recordId).toString() }
        override fun forget(target: String): String = onWorker("forget") { memory.forget(target, LocalDateTime.now()).toString() }
        override fun snapshot(forCloud: Boolean): String = onWorker("snapshot") { memory.snapshot(forCloud).toString() }
        override fun unmask(text: String): String = lastCloudPacket?.unmask(text) ?: text
        override fun stats(): String = latency.toJson().toString()
        override fun enroll(personId: String, name: String, bindingsJson: String): String = onWorker("enroll") {
            val bindings = (json.parseToJsonElement(bindingsJson) as JsonArray).map {
                val b = it as JsonObject
                (b["kind"] as JsonPrimitive).content to (b["value"] as JsonPrimitive).content
            }
            val p = memory.enroll(personId, name, bindings)
            buildJsonObject { put("id", p.id); put("name", p.name); put("bindings", bindings.size) }.toString()
        }
    }

    /**
     * Started (not only bound) so the session outlives any one client, and foreground with type location:
     * location is delivered only to the current user's apps, and a background one is throttled to a few
     * fixes an hour, which is not enough to segment trips. Run in the driver's user, not the headless user 0.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Assistant memory", android.app.NotificationManager.IMPORTANCE_MIN))
        val note = android.app.Notification.Builder(this, CHANNEL).setContentTitle("Assistant memory")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history).setOngoing(true).build()
        startForeground(NOTIFICATION_ID, note,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        handler.post {
            signals?.stop(); location?.stop(); phone?.stop()
            session.persistPlaces()   // already saved after every trip; this only catches dwell counted since
            memory.close()
            (llm as? AutoCloseable)?.close()
        }
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val TAG = "carmem"
        private const val CHANNEL = "carmem"
        private const val NOTIFICATION_ID = 0xca7
        /** Gemma 3n E2B in .litertlm form, pushed to noBackupFilesDir. */
        const val MODEL_FILE = "gemma-3n-E2B-it-int4.litertlm"
    }
}

/**
 * Proposals go out as a broadcast the assistant UI / TTS layer subscribes to.
 *
 * Sent twice: to the hosting app itself, and to any app that holds the signature permission the service is bound
 * with, so an assistant in its own package receives them. That assistant registers a receiver for [ACTION] at
 * runtime (a manifest receiver does not get implicit broadcasts) and declares `uses-permission` [PERMISSION].
 */
object ProposalBus {
    const val ACTION = "carmem.android.PROPOSALS"
    const val PERMISSION = "carmem.permission.BIND_MEMORY"
    fun publish(ctx: android.content.Context, props: List<carmem.Proposal>) {
        val payload = JsonArray(props.map { p -> buildJsonObject { put("id", p.id); put("kind", p.kind); put("record_id", p.recordId); put("tier", p.tier.wire); put("text", p.text); put("action", JsonObject(p.action)) } }).toString()
        ctx.sendBroadcast(Intent(ACTION).setPackage(ctx.packageName).putExtra("proposals", payload))
        ctx.sendBroadcast(Intent(ACTION).putExtra("proposals", payload), PERMISSION)
    }
}
