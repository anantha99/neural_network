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
import carmem.FixtureReplay
import carmem.IdentitySignal
import carmem.isGuest
import carmem.iso
import carmem.parseIso
import carmem.car.CarEvent
import carmem.car.CarSession
import carmem.car.DemoClock
import carmem.car.IgnitionChanged
import carmem.car.LocationFix
import carmem.car.OccupantObserved
import carmem.car.PlaceNamed
import carmem.car.PlaceResolver
import carmem.car.SettingChanged
import carmem.car.UtteranceHeard
import carmem.car.VehicleFault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private lateinit var db: AndroidSqlDriver
    private lateinit var policy: Policy
    /** "Now" for the memory and every platform adapter; the demo pins it with [ICarMemory.setClock]. */
    private val clock = DemoClock()
    private lateinit var assistant: Assistant
    private var llm: carmem.LlmClient? = null
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
        policy = if (policyFile.exists()) Policy.fromJson(policyFile.readText()) else Policy()
        // On-device model if one has been provisioned, else the dev-only cloud Gemma; without either extraction stays rule-based.
        // gemini.key: API key, optional second line = model. Used for extraction when no on-device model is present,
        // and for the assistant's chat loop either way (there is no on-device chat model yet).
        val gemini: List<String>? = File(dir, GeminiLlmClient.KEY_FILE).takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
        llm = File(dir, MODEL_FILE).takeIf { it.exists() }?.let { LiteRtLmClient(it.absolutePath, cacheDir.absolutePath) }
            ?: gemini?.let { GeminiLlmClient(it[0], it.getOrElse(1) { GeminiLlmClient.DEFAULT_MODEL }) }
        db = AndroidSqlDriver(File(dir, "carmem.db").absolutePath)
        memory = CarMemory(db, policy, llm, clock = clock)
        val places = PlaceResolver().also { pr -> File(dir, "places.tsv").takeIf { it.exists() }?.let { pr.restore(it.readText()) } }
        session = CarSession(
            memory, places,
            asyncExtraction = true,
            runOnMemoryThread = { r -> handler.post(r) },
            onDriver = { occ, person -> DriverBus.publish(this, occ.driverId, person?.name, occ.isGuest) },
            onProposals = { props -> ProposalBus.publish(this, props) },
            // Extraction results land on the worker thread; tell the UI memory changed so it can refresh.
            onExtracted = { episodeId, stored -> Log.i(TAG, "extracted episode $episodeId: $stored"); MemoryBus.publish(this) },
            onExtractionError = { episodeId, t -> Log.w(TAG, "extraction failed for episode $episodeId", t) },
        )
        val chatModel: ChatModel? = gemini?.let { GeminiChatModel(it[0], it.getOrElse(1) { GeminiLlmClient.DEFAULT_MODEL }) }
        assistant = Assistant(memory, chatModel) { episodeId, scope, text, now -> session.extraction!!.submit(episodeId, scope, text, now) }
        handler.post {
            signals = VehicleSignals(this, ::post, clock).also { it.start() }
            location = LocationFeed(this, ::post, clock).also { it.start() }
            phone = PhoneSignal(this, ::post, clock).also { it.start() }
        }
        Log.i(TAG, "carmem service up, db=${File(dir, "carmem.db")}, llm=${llm?.javaClass?.simpleName ?: "rules"}, chat=${chatModel?.javaClass?.simpleName ?: "none"}")
    }

    /** Every car event funnels through here onto the worker thread. */
    fun post(e: CarEvent) { Log.d(TAG, "event $e"); handler.post { runCatching { session.onEvent(e) }.onFailure { Log.w(TAG, "event failed: $e", it) } } }

    private fun <T> onWorker(block: () -> T): T {
        if (Thread.currentThread() == worker) return block()
        var result: Result<T>? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post { result = runCatching(block); latch.countDown() }
        latch.await()
        return result!!.getOrThrow()
    }

    private fun proposalJson(p: carmem.Proposal): JsonObject =
        buildJsonObject { put("id", p.id); put("kind", p.kind); put("record_id", p.recordId); put("scope", p.scope); put("tier", p.tier.wire); put("text", p.text); put("action", JsonObject(p.action)) }

    private val binder = object : ICarMemory.Stub() {
        /** Intents are handled at once; extraction (a model call) runs on the session's extraction queue, not here. */
        override fun hear(text: String, speakerId: String?): String = onWorker {
            val now = memory.clock()
            val r = memory.hear(text, speakerId, now, extract = false)
            if ((r["deferred"] as? JsonPrimitive)?.booleanOrNull == true)
                session.extraction!!.submit((r["episode"] as JsonPrimitive).content.toLong(), (r["scope"] as JsonPrimitive).content, text, now)
            r.toString()
        }
        override fun context(query: String, destination: String): String = onWorker {
            val p = memory.context(query, destination, memory.clock())
            if (destination == "cloud") lastCloudPacket = p
            buildJsonObject { put("rendered", p.render()); put("packet", p.toJson()) }.toString()
        }
        override fun recall(query: String, k: Int): String = onWorker { memory.recall(query, k, memory.clock()).toString() }
        override fun proactive(location: String?): String = onWorker {
            JsonArray(memory.proactive(memory.clock(), location).map(::proposalJson)).toString()
        }
        override fun handleTool(name: String, argsJson: String): String = onWorker { memory.handleTool(name, json.parseToJsonElement(argsJson) as JsonObject).toString() }
        override fun explain(recordId: String): String = onWorker { memory.explain(recordId).toString() }
        override fun forget(target: String): String = onWorker { memory.forget(target, memory.clock()).toString() }
        override fun snapshot(forCloud: Boolean): String = onWorker { memory.snapshot(forCloud).toString() }
        override fun unmask(text: String): String = lastCloudPacket?.unmask(text) ?: text
        override fun enroll(personId: String, name: String, bindingsJson: String): String = onWorker {
            val bindings = (json.parseToJsonElement(bindingsJson) as JsonArray).map {
                val b = it as JsonObject
                (b["kind"] as JsonPrimitive).content to (b["value"] as JsonPrimitive).content
            }
            val p = memory.enroll(personId, name, bindings)
            buildJsonObject { put("id", p.id); put("name", p.name); put("bindings", bindings.size) }.toString()
        }

        /**
         * Replay a fixture (fixtures/household_seed7_w8.json) into the live memory: weeks of history in seconds.
         * Skipped when every fixture person is already enrolled, because a second replay double-counts evidence;
         * to re-seed, delete carmem.db (see the devhost README). The service keeps its own policy (the fixture's
         * policy equals the default apart from Kotlin-only keys).
         */
        override fun seed(fixtureJson: String): String = onWorker {
            val fixture = json.parseToJsonElement(fixtureJson).jsonObject
            val ids = fixture["persons"]?.jsonArray?.map { it.jsonObject["id"]!!.jsonPrimitive.content } ?: emptyList()
            if (ids.isNotEmpty() && ids.all { memory.store.getPerson(it) != null })
                return@onWorker buildJsonObject { put("already_seeded", true); put("persons", JsonArray(ids.map { JsonPrimitive(it) })) }.toString()
            // Close any open driving session through the session, so its state matches the memory's after replay.
            if (memory.occupancy != null) session.onEvent(IgnitionChanged(memory.clock(), false))
            val started = System.currentTimeMillis()
            // Replay on the rules, like the reference that produced the fixture: a model here makes seeding slow
            // (one network call per utterance) and the history non-deterministic. Same database, same thread.
            val r = FixtureReplay(CarMemory(db, policy, llm = null, clock = clock)).replay(fixture)
            Log.i(TAG, "seeded ${r.events} events in ${System.currentTimeMillis() - started} ms")
            buildJsonObject {
                put("events", r.events); put("proposals", r.proposals.size)
                put("persons", JsonArray(r.persons.map { JsonPrimitive(it) })); put("lastTs", iso(r.lastTs))
            }.toString()
        }

        override fun setClock(isoOrEmpty: String): String = onWorker {
            if (isoOrEmpty.isBlank()) clock.reset() else clock.set(parseIso(isoOrEmpty.trim()))
            buildJsonObject { put("now", iso(memory.clock())); put("pinned", clock.isPinned) }.toString()
        }

        override fun injectEvent(eventJson: String): String = onWorker {
            val e = eventFromJson(json.parseToJsonElement(eventJson).jsonObject)
            session.onEvent(e)
            buildJsonObject { put("ok", true); put("event", e.toString()); put("ts", iso(e.ts)) }.toString()
        }

        override fun chat(text: String, destination: String): String = onWorker {
            assistant.ask(text, destination).toJson().toString()
        }

        override fun whoami(): String = onWorker {
            val guestScopes = memory.store.scopesPresent().count(::isGuest)
            val occ = memory.occupancy
            if (occ == null) buildJsonObject { put("guest_scopes_remaining", guestScopes) }.toString()
            else buildJsonObject {
                put("driver_id", occ.driverId); put("name", if (occ.isGuest) null else memory.store.getPerson(occ.driverId)?.name)
                put("is_guest", occ.isGuest); put("now", iso(memory.clock())); put("guest_scopes_remaining", guestScopes)
            }.toString()
        }
    }

    /**
     * A [CarEvent] from JSON, so a demo can drive the session without the emulator's VHAL. `type` is one of
     * ignition {on}, location {lat, lon, speed_mps?}, setting {key, value}, occupant {kind, value, confidence?, role?}
     * (IdentitySignal field names from fixtures/wire-schema.json), utterance {text, speaker?}, fault {key, value},
     * place {name}. `ts` (ISO local time) defaults to the memory clock.
     */
    private fun eventFromJson(o: JsonObject): CarEvent {
        fun str(k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
        fun num(k: String): Double? = (o[k] as? JsonPrimitive)?.doubleOrNull
        val ts: LocalDateTime = str("ts")?.let(::parseIso) ?: memory.clock()
        return when (val type = str("type")) {
            "ignition" -> IgnitionChanged(ts, (o["on"] as? JsonPrimitive)?.booleanOrNull ?: true)
            "location" -> LocationFix(ts, num("lat")!!, num("lon")!!, num("speed_mps"))
            "setting" -> SettingChanged(ts, str("key")!!, o["value"]!!)
            "occupant" -> {
                val sig = (o["signal"] as? JsonObject) ?: o
                fun s(k: String): String? = (sig[k] as? JsonPrimitive)?.contentOrNull
                OccupantObserved(ts, IdentitySignal(s("kind")!!, s("value")!!, (sig["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 1.0, s("role") ?: "driver"))
            }
            "utterance" -> UtteranceHeard(ts, str("text")!!, str("speaker"))
            "fault" -> VehicleFault(ts, str("key")!!, o["value"]!!)
            "place" -> PlaceNamed(ts, str("name")!!)
            else -> throw IllegalArgumentException("unknown event type $type")
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
        session.close() // stops the extraction queue; queued extractions are dropped, their utterances stay logged
        handler.post {
            signals?.stop(); location?.stop(); phone?.stop()
            File(noBackupFilesDir, "places.tsv").writeText(session.places.snapshot())
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

/** The resolved driver after each ignition on, for the greeting. Extras: driver_id, name (null for guests), is_guest. */
object DriverBus {
    const val ACTION = "carmem.android.DRIVER"
    fun publish(ctx: android.content.Context, driverId: String, name: String?, isGuest: Boolean) {
        ctx.sendBroadcast(Intent(ACTION).setPackage(ctx.packageName)
            .putExtra("driver_id", driverId).putExtra("name", name).putExtra("is_guest", isGuest))
    }
}

/** Background extraction applied something (or tried to): memory may have changed, refresh what is on screen. */
object MemoryBus {
    const val ACTION = "carmem.android.MEMORY_CHANGED"
    fun publish(ctx: android.content.Context) = ctx.sendBroadcast(Intent(ACTION).setPackage(ctx.packageName))
}

/** Proposals go out as a local broadcast the assistant UI / TTS layer subscribes to. */
object ProposalBus {
    const val ACTION = "carmem.android.PROPOSALS"
    fun publish(ctx: android.content.Context, props: List<carmem.Proposal>) {
        val payload = JsonArray(props.map { p -> buildJsonObject { put("id", p.id); put("kind", p.kind); put("tier", p.tier.wire); put("text", p.text); put("action", JsonObject(p.action)) } })
        ctx.sendBroadcast(Intent(ACTION).setPackage(ctx.packageName).putExtra("proposals", payload.toString()))
    }
}
