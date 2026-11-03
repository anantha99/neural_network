package carmem.devhost

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import carmem.android.DriverBus
import carmem.android.ICarMemory
import carmem.android.MemoryBus
import carmem.android.ProposalBus
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The demo screen: transcript (left), proposals (middle), what the car knows (right), demo controls on top.
 *
 * Every ICarMemory call blocks on the service's worker thread, and chat blocks on the cloud model too, so
 * all of them run on one background executor (in order) and post their result to the main thread.
 * Views are built in code: no layout XML, no AndroidX.
 */
class DemoActivity : Activity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var transcript: LinearLayout
    private lateinit var transcriptScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var cards: LinearLayout
    private lateinit var locationField: EditText
    private lateinit var knows: TextView
    private lateinit var whoLine: TextView
    private lateinit var destLocal: Button
    private lateinit var destCloud: Button
    private var knowsDest = "local"

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        register(driverReceiver, DriverBus.ACTION)
        register(proposalReceiver, ProposalBus.ACTION)
        register(memoryReceiver, MemoryBus.ACTION)
        system("Demo ready. Seed, set the clock, then let Asha in.")
        main.postDelayed({ refreshKnows() }, 1_000) // the service bind may still be in flight
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(driverReceiver) }
        runCatching { unregisterReceiver(proposalReceiver) }
        runCatching { unregisterReceiver(memoryReceiver) }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun register(r: BroadcastReceiver, action: String) {
        val f = IntentFilter(action)
        // The service broadcasts with setPackage(ourPackage) from this same app, so a non-exported receiver hears it.
        // VERIFY: registerReceiver(receiver, filter, flags) with Context.RECEIVER_NOT_EXPORTED (API 33) delivers these.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
    }

    private val driverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val guest = intent.getBooleanExtra("is_guest", true)
            val name: String? = intent.getStringExtra("name")
            if (guest) line("Car: Hello. I don't recognise you, so nothing from this trip will be kept.", C_GREET)
            else line("Car: Good to see you, ${name ?: intent.getStringExtra("driver_id")}.", C_GREET)
            refreshKnows()
        }
    }

    private val proposalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val payload: String = intent.getStringExtra("proposals") ?: return
            showProposals(JSONArray(payload), announce = true)
        }
    }

    private val memoryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            system("(memory updated in the background)")
            refreshKnows()
        }
    }

    // ------------------------------------------------------------------ binder calls

    /** Run `block` against the service off the main thread; deliver the result (or a system line on failure) on the main thread. */
    private fun call(label: String, block: (ICarMemory) -> String, onResult: (String) -> Unit) {
        executor.execute {
            val m: ICarMemory? = (application as DevHostApp).memory
            val res: Result<String> = if (m == null) Result.failure(IllegalStateException("service not bound yet")) else runCatching { block(m) }
            main.post { res.fold(onResult) { t -> system("$label failed: ${t.message}") } }
        }
    }

    private fun event(vararg kv: Pair<String, Any>): String {
        val o = JSONObject()
        for ((k, v) in kv) o.put(k, v)
        return o.toString()
    }

    /** Post events in order, then run `after` on the main thread. */
    private fun inject(label: String, events: List<String>, after: () -> Unit) {
        call(label, { m -> events.forEach { m.injectEvent(it) }; "ok" }) { after() }
    }

    // ------------------------------------------------------------------ actions

    private fun seed() {
        system("Seeding eight weeks of household history…")
        call("seed", { m -> m.seed(assets.open(ProbeReceiver.FIXTURE_ASSET).bufferedReader().use { it.readText() }) }) { r ->
            val o = JSONObject(r)
            if (o.optBoolean("already_seeded")) system("Already seeded (${o.optJSONArray("persons")}). Delete carmem.db to start over.")
            else system("Seeded ${o.optInt("events")} events for ${o.optJSONArray("persons")}, ${o.optInt("proposals")} proposals made; history ends ${o.optString("lastTs")}.")
            refreshKnows()
        }
    }

    private fun setClock(iso: String) {
        call("clock", { m -> m.setClock(iso) }) { r ->
            val o = JSONObject(r)
            system(if (o.optBoolean("pinned")) "Clock set: ${o.optString("now")}" else "Clock reset to wall time: ${o.optString("now")}")
            refreshKnows()
        }
    }

    private fun ashaArrives() {
        system("Asha gets in: key fob, phone, at home, ignition on.")
        inject("asha", listOf(
            event("type" to "ignition", "on" to false),
            event("type" to "occupant", "kind" to "key_fob", "value" to "fob-asha"),
            event("type" to "occupant", "kind" to "phone", "value" to "asha-phone"),
            event("type" to "location", "lat" to HOME_LAT, "lon" to HOME_LON, "speed_mps" to 0.0),
            event("type" to "place", "name" to "home"),
            event("type" to "ignition", "on" to true),
        )) { refreshKnows() }
    }

    private fun guestArrives() {
        system("A stranger gets in: voice only (v-stranger, 0.6), ignition on.")
        inject("guest", listOf(
            event("type" to "ignition", "on" to false),
            event("type" to "occupant", "kind" to "voice", "value" to "v-stranger", "confidence" to 0.6),
            event("type" to "location", "lat" to HOME_LAT, "lon" to HOME_LON, "speed_mps" to 0.0),
            event("type" to "ignition", "on" to true),
        )) { refreshKnows() }
    }

    private fun ignitionOff() {
        inject("ignition off", listOf(event("type" to "ignition", "on" to false))) {
            clearCards()
            call("whoami", { m -> m.whoami() }) { r ->
                system("Ignition off. Guest records left on the device: ${JSONObject(r).optInt("guest_scopes_remaining")}.")
                refreshKnows()
            }
        }
    }

    private fun say(destination: String) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        line("You: $text", C_DRIVER)
        val thinking = line(if (destination == "cloud") "thinking… (cloud)" else "thinking…", C_SYSTEM)
        call("chat", { m -> m.chat(text, destination) }) { r ->
            transcript.removeView(thinking)
            val o = JSONObject(r)
            val intent = o.optString("intent", "none")
            if (destination == "cloud") showCloudTrace(o)
            line("Car: ${o.optString("reply")}", C_ASSISTANT)
            if (intent != "none") system("(answered by the rule for \"$intent\", offline)")
            if (!o.isNull("note")) system("(${o.optString("note")})")
            if (intent == "reject") clearCards()
            refreshKnows()
        }
    }

    /** Beat 7: what went up (masked), what the model asked the car to do, what it said, what the driver hears. */
    private fun showCloudTrace(o: JSONObject) {
        line("→ cloud packet, ${o.optInt("masked_count")} personal field(s) masked:", C_CLOUD)
        line(o.optString("packet_rendered"), C_CLOUD, mono = true, small = true)
        val calls: JSONArray = o.optJSONArray("tool_calls") ?: JSONArray()
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            line("⚙ tool ${c.optString("name")}(${c.optJSONObject("args")}) ran on the car; sent back: ${c.opt("result").toString().take(600)}", C_CLOUD, mono = true, small = true)
        }
        if (o.optString("raw_reply").isNotEmpty()) line("← model said: ${o.optString("raw_reply")}", C_CLOUD)
    }

    private fun answer(yes: Boolean) {
        val word = if (yes) "yes" else "no"
        line("You: $word", C_DRIVER)
        call("hear", { m -> m.hear(word, null) }) { r ->
            val applied = JSONObject(r).optJSONObject("correction")?.optBoolean("applied") == true
            line("Car: ${if (!applied) "Okay." else if (yes) "Okay, doing it." else "Okay, skipped."}", C_ASSISTANT)
            if (!yes && applied) clearCards()
            refreshKnows()
        }
    }

    private fun proposeNow() {
        val loc = locationField.text.toString().trim().ifEmpty { "home" }
        call("proactive", { m -> m.proactive(loc) }) { r ->
            val arr = JSONArray(r)
            if (arr.length() == 0) system("Nothing to propose at $loc right now.")
            showProposals(arr, announce = true)
        }
    }

    private fun refreshKnows() {
        val dest = knowsDest
        call("context", { m -> m.context("", dest) + "\u0000" + m.whoami() }) { r ->
            val (ctx, who) = r.split("\u0000", limit = 2).let { it[0] to it.getOrElse(1) { "{}" } }
            knows.text = JSONObject(ctx).optString("rendered")
            val w = JSONObject(who)
            whoLine.text = when {
                !w.has("driver_id") -> "Who am I: nobody (ignition off). Guest records on device: ${w.optInt("guest_scopes_remaining")}"
                w.optBoolean("is_guest") -> "Who am I: guest ${w.optString("driver_id")} · ${w.optString("now")}"
                else -> "Who am I: ${w.optString("name")} (${w.optString("driver_id")}) · ${w.optString("now")}"
            }
        }
    }

    // ------------------------------------------------------------------ proposals pane

    private fun clearCards() = cards.removeAllViews()

    private fun showProposals(arr: JSONArray, announce: Boolean) {
        clearCards()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val tier = p.optString("tier")
            val text = p.optString("text")
            if (announce) line(if (tier == "act") "Car (done unasked): $text" else "Car: $text", C_ASSISTANT)
            cards.addView(card(tier, text))
        }
    }

    private fun card(tier: String, text: String): View {
        val box = vertical().apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#BDBDBD")); cornerRadius = dp(8).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        val badge = TextView(this).apply {
            this.text = tier.uppercase()
            setTextColor(Color.WHITE); textSize = 12f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply { setColor(Color.parseColor(TIER_COLORS[tier] ?: "#757575")); cornerRadius = dp(4).toFloat() }
        }
        box.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(label(text, 18f, C_TEXT))
        box.addView(row(button("Yes") { answer(true) }, button("No") { answer(false) }))
        return box
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val root = vertical().apply { setBackgroundColor(Color.parseColor("#FAFAFA")); setPadding(dp(12), dp(8), dp(12), dp(8)) }

        val controls = row(
            button("Seed") { seed() },
            button("Clock → Mon 07:38") { setClock("2026-10-12T07:38:00") },
            button("Clock → Fri 18:28") { setClock("2026-10-16T18:28:00") },
            button("Clock reset") { setClock("") },
            button("Asha arrives") { ashaArrives() },
            button("Guest arrives") { guestArrives() },
            button("Ignition off") { ignitionOff() },
        )
        root.addView(HorizontalScrollView(this).apply { addView(controls) }, matchWrap())

        val panes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panes.addView(transcriptPane(), paneParams())
        panes.addView(proposalsPane(), paneParams())
        panes.addView(knowsPane(), paneParams())
        root.addView(panes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun transcriptPane(): View {
        val pane = pane("Transcript")
        transcript = vertical()
        transcriptScroll = ScrollView(this).apply { addView(transcript) }
        pane.addView(transcriptScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        input = EditText(this).apply { hint = "Say something…"; setSingleLine(true); setTextColor(C_TEXT) }
        val inputRow = row(button("Say") { say("local") }, button("Ask cloud") { say("cloud") })
        inputRow.addView(input, 0, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pane.addView(inputRow, matchWrap())
        // Typing on the emulator is slow: one tap fills the input with each beat's line.
        val phrases = row(*PHRASES.map { p -> button(p.take(28) + if (p.length > 28) "…" else "") { input.setText(p); input.setSelection(p.length) } }.toTypedArray())
        pane.addView(HorizontalScrollView(this).apply { addView(phrases) }, matchWrap())
        pane.addView(row(button("Yes") { answer(true) }, button("No") { answer(false) }), matchWrap())
        return pane
    }

    private fun proposalsPane(): View {
        val pane = pane("Proposals")
        locationField = EditText(this).apply { setText("home"); setSingleLine(true); setTextColor(C_TEXT) }
        val r = row(button("Propose now") { proposeNow() })
        r.addView(locationField, 0, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pane.addView(r, matchWrap())
        cards = vertical()
        pane.addView(ScrollView(this).apply { addView(cards) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return pane
    }

    private fun knowsPane(): View {
        val pane = pane("What the car knows")
        whoLine = label("Who am I: …", 14f, C_TEXT)
        pane.addView(whoLine, matchWrap())
        destLocal = button("Local") { knowsDest = "local"; markDest(); refreshKnows() }
        destCloud = button("Cloud") { knowsDest = "cloud"; markDest(); refreshKnows() }
        pane.addView(row(destLocal, destCloud, button("Refresh") { refreshKnows() }), matchWrap())
        markDest()
        knows = label("", 13f, C_TEXT).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        pane.addView(ScrollView(this).apply { addView(knows) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return pane
    }

    private fun markDest() {
        destLocal.alpha = if (knowsDest == "local") 1f else 0.5f
        destCloud.alpha = if (knowsDest == "cloud") 1f else 0.5f
    }

    // ------------------------------------------------------------------ view helpers

    private fun line(text: String, color: Int, mono: Boolean = false, small: Boolean = false): TextView {
        val tv = label(text, if (small) 12f else 16f, color).apply { if (mono) typeface = Typeface.MONOSPACE; setPadding(0, dp(3), 0, dp(3)) }
        transcript.addView(tv, matchWrap())
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    private fun system(text: String) { line(text, C_SYSTEM) }

    private fun pane(title: String): LinearLayout = vertical().apply {
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(label(title, 20f, C_TEXT).apply { setTypeface(typeface, Typeface.BOLD) }, matchWrap())
    }

    private fun vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    private fun label(text: String, size: Float, color: Int): TextView = TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun paneParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The fixture has no coordinates; this is the home the demo (and sim/drive.sh) uses. */
        const val HOME_LAT = 12.9716
        const val HOME_LON = 77.5946

        val C_TEXT: Int = Color.parseColor("#212121")
        val C_DRIVER: Int = Color.parseColor("#1565C0")
        val C_ASSISTANT: Int = Color.parseColor("#2E7D32")
        val C_GREET: Int = Color.parseColor("#6A1B9A")
        val C_SYSTEM: Int = Color.parseColor("#757575")
        val C_CLOUD: Int = Color.parseColor("#E65100")
        val TIER_COLORS: Map<String, String> = mapOf("act" to "#2E7D32", "suggest" to "#1565C0", "silent" to "#757575", "never" to "#C62828")

        val PHRASES: List<String> = listOf(
            "no",
            "my daughter is allergic to penicillin",
            "why do you think I like 21 degrees?",
            "forget that I go to the office",
            "my home is 12 Elm Street",
            "Anything I should keep in mind for the school run today?",
        )
    }
}
