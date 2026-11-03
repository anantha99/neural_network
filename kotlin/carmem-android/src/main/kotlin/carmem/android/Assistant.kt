package carmem.android

import android.util.Log
import carmem.CarMemory
import carmem.ContextPacket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDateTime

// ------------------------------------------------------------------ chat model abstraction

/** One function call the model asked for. `id` is the provider's call id, when it sends one. */
data class ToolCall(val name: String, val args: JsonObject, val id: String?)

/** What the model said: plain text, or tool calls to run on the car. `raw` is the provider's turn, echoed back verbatim. */
sealed interface ChatReply {
    val raw: JsonObject?
    data class Text(val text: String, override val raw: JsonObject? = null) : ChatReply
    data class Calls(val calls: List<ToolCall>, override val raw: JsonObject? = null) : ChatReply
}

/** One turn of the conversation sent to the model. */
sealed interface ChatTurn {
    data class User(val text: String) : ChatTurn
    data class Model(val reply: ChatReply) : ChatTurn
    data class ToolResults(val results: List<Pair<ToolCall, JsonObject>>) : ChatTurn
}

/** A text-in / text-out chat model with function calling. Calls block (network); never call on the main thread. */
interface ChatModel {
    fun chat(system: String, messages: List<ChatTurn>, tools: List<JsonObject>): ChatReply
}

/**
 * [ChatModel] over the Gemini API `generateContent` with function calling (Gemma 4 by default, the same
 * dev-only cloud model [GeminiLlmClient] uses for extraction). Tool specs are the core's neutral shape
 * ([carmem.MemoryTools.neutral]): `parameters` is passed as the Gemini `parameters` schema with
 * `additionalProperties` stripped, because the Gemini schema subset rejects it.
 */
// VERIFY: that gemma-4-26b-a4b-it accepts `tools` / returns `functionCall` parts on the Gemini API (Gemma 3 there
// did not). If it answers 400 for tools, Assistant falls back to a packet answer with the error in `note`; then put
// a Gemini model id (e.g. a gemini-2.5-flash variant) on line 2 of gemini.key for the chat demo.
class GeminiChatModel(private val apiKey: String, private val model: String = GeminiLlmClient.DEFAULT_MODEL) : ChatModel {
    override fun chat(system: String, messages: List<ChatTurn>, tools: List<JsonObject>): ChatReply {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            put("contents", JsonArray(messages.map(::content)))
            // VERIFY: Gemini function declarations take an OpenAPI-subset schema; lowercase "object"/"string" types are
            // accepted by the REST API in Google's own examples. If a 400 names the schema, uppercase the "type" values.
            if (tools.isNotEmpty()) putJsonArray("tools") { add(buildJsonObject { put("functionDeclarations", JsonArray(tools.map(::declaration))) }) }
            putJsonObject("generationConfig") { put("temperature", 0.2) }
        }
        val started = System.currentTimeMillis()
        val res = geminiPost(apiKey, model, body.toString())
        val root = Json.parseToJsonElement(res).jsonObject
        val content: JsonObject? = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content") as? JsonObject
        val parts: List<JsonObject> = (content?.get("parts") as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        // Gemma 4 thinks first; thought parts are not the answer (same rule as GeminiLlmClient).
        val answerParts = parts.filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
        val calls = answerParts.mapNotNull { p ->
            val fc = p["functionCall"] as? JsonObject ?: return@mapNotNull null
            ToolCall(fc["name"]!!.jsonPrimitive.content, fc["args"] as? JsonObject ?: JsonObject(emptyMap()), fc["id"]?.jsonPrimitive?.contentOrNull)
        }
        Log.i(CarMemoryService.TAG, "chat $model ${System.currentTimeMillis() - started} ms: ${if (calls.isNotEmpty()) "calls ${calls.map { it.name }}" else "text"}")
        if (calls.isNotEmpty()) return ChatReply.Calls(calls, content)
        val text = answerParts.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }.joinToString("").trim()
        return ChatReply.Text(text, content)
    }

    private fun content(turn: ChatTurn): JsonObject = when (turn) {
        is ChatTurn.User -> buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", turn.text) }) } }
        // Echo the model's own turn verbatim: it may carry thought signatures the API wants back with the function call.
        is ChatTurn.Model -> turn.reply.raw ?: when (val r = turn.reply) {
            is ChatReply.Text -> buildJsonObject { put("role", "model"); putJsonArray("parts") { add(buildJsonObject { put("text", r.text) }) } }
            is ChatReply.Calls -> buildJsonObject {
                put("role", "model")
                put("parts", JsonArray(r.calls.map { c -> buildJsonObject { putJsonObject("functionCall") { put("name", c.name); put("args", c.args); c.id?.let { put("id", it) } } } }))
            }
        }
        // VERIFY: current Gemini docs send function responses in a "user" turn (older docs used role "function").
        is ChatTurn.ToolResults -> buildJsonObject {
            put("role", "user")
            put("parts", JsonArray(turn.results.map { (c, result) ->
                buildJsonObject { putJsonObject("functionResponse") { put("name", c.name); c.id?.let { put("id", it) }; put("response", result) } }
            }))
        }
    }

    private fun declaration(spec: JsonObject): JsonObject = buildJsonObject {
        put("name", spec["name"]!!)
        put("description", spec["description"]!!)
        spec["parameters"]?.let { put("parameters", stripAdditionalProperties(it)) }
    }

    companion object {
        /** Gemini's schema subset rejects `additionalProperties`; drop it at every depth. */
        fun stripAdditionalProperties(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.filterKeys { it != "additionalProperties" }.mapValues { (_, v) -> stripAdditionalProperties(v) })
            is JsonArray -> JsonArray(e.map(::stripAdditionalProperties))
            else -> e
        }
    }
}

// ------------------------------------------------------------------ the assistant loop

/** One tool call the model made and what the car sent back (masked when the destination is the cloud). */
data class ToolTrace(val name: String, val args: JsonObject, val result: JsonElement)

data class AssistantResult(
    /** What the driver hears: un-masked. */
    val reply: String,
    /** What the model said, before un-masking (equal to [reply] for local and for intents). */
    val rawReply: String,
    val packetRendered: String,
    val maskedCount: Int,
    val toolCalls: List<ToolTrace>,
    val destination: String,
    /** The rule-based intent that answered, if one did (reject / accept / forget / explain); "none" when the model answered. */
    val intent: String = "none",
    /** Why the model was not used, or what went wrong with it. */
    val note: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("reply", reply); put("raw_reply", rawReply); put("packet_rendered", packetRendered); put("masked_count", maskedCount)
        put("tool_calls", JsonArray(toolCalls.map { t -> buildJsonObject { put("name", t.name); put("args", t.args); put("result", t.result) } }))
        put("destination", destination); put("intent", intent); put("note", note)
    }
}

/**
 * A minimal text-in / text-out assistant over the memory. Driving-time intents (no / yes / forget / why)
 * are answered by rules, offline and at once; everything else goes to [model] with the context packet
 * and the six memory tools, which always execute here on the car.
 *
 * Threading: [ask] must run on the memory thread (the service calls it inside `onWorker`), because it
 * calls [CarMemory] directly, including for tool calls. The model calls inside it are slow network
 * calls (seconds to minutes on the free tier), so car events and other binder calls queue behind a chat.
 * Acceptable for the demo; a real assistant would run the model loop on its own thread and hop to the
 * memory thread only for `context` / `handleTool`.
 *
 * For `destination == "cloud"` the packet is the masked one, the driver's text and every tool result are
 * masked with the packet's token map before they leave, tool arguments are un-masked before they run,
 * and the final reply is un-masked with [ContextPacket.unmask].
 */
class Assistant(
    private val memory: CarMemory,
    private val model: ChatModel?,
    /** Hands a logged utterance to background extraction (the service wires CarSession.extraction.submit). */
    private val deferExtraction: (episodeId: Long, scope: String, text: String, now: LocalDateTime) -> Unit,
) {
    fun ask(text: String, destination: String): AssistantResult {
        val dest = if (destination == "cloud") "cloud" else "local"
        val now: LocalDateTime = memory.clock()
        if (memory.occupancy == null)
            return AssistantResult("Nobody is in the driver's seat yet. Turn the ignition on first.", "", "", 0, emptyList(), dest, note = "no session")

        // Log the utterance first; intents are handled now, extraction runs in the background.
        val heard: JsonObject = memory.hear(text, null, now, extract = false)
        val intent: String = heard["intent"]?.jsonPrimitive?.contentOrNull ?: "none"
        if (heard["deferred"]?.jsonPrimitive?.booleanOrNull == true)
            deferExtraction(heard["episode"]!!.jsonPrimitive.longOrNull!!, heard["scope"]!!.jsonPrimitive.content, text, now)
        intentReply(intent, heard, text)?.let { r ->
            val packet = memory.context(text, dest, now)
            return AssistantResult(r, r, packet.render(), packet.masked.size, emptyList(), dest, intent = intent)
        }

        val packet: ContextPacket = memory.context(text, dest, now)
        val rendered: String = packet.render()
        val cloud = dest == "cloud"
        if (model == null) {
            val r = offlineAnswer(packet)
            return AssistantResult(r, r, rendered, packet.masked.size, emptyList(), dest, note = "no chat model configured (no ${GeminiLlmClient.KEY_FILE}); answered from the packet")
        }

        val system = "$SYSTEM_PROMPT\n\n$rendered"
        val turns = mutableListOf<ChatTurn>(ChatTurn.User(if (cloud) mask(text, packet) else text))
        val traces = mutableListOf<ToolTrace>()
        val tools: List<JsonObject> = memory.toolSpecs("neutral")
        try {
            for (round in 0 until MAX_ROUNDS) {
                when (val reply = model.chat(system, turns, tools)) {
                    is ChatReply.Text -> {
                        val raw = reply.text.ifEmpty { "Sorry, I have no answer for that." }
                        val shown = if (cloud) packet.unmask(raw) else raw
                        return AssistantResult(shown, raw, rendered, packet.masked.size, traces, dest)
                    }
                    is ChatReply.Calls -> {
                        turns += ChatTurn.Model(reply)
                        val results = reply.calls.map { c ->
                            val args: JsonObject = if (cloud) unmaskArgs(c.args, packet) else c.args
                            val result: JsonObject = runTool(c.name, args, now, text, heard)
                            val sent: JsonObject = if (cloud) maskJson(result, packet) as JsonObject else result
                            traces += ToolTrace(c.name, c.args, sent)
                            c to sent
                        }
                        turns += ChatTurn.ToolResults(results)
                    }
                }
            }
            val r = "Sorry, I couldn't finish that."
            return AssistantResult(r, r, rendered, packet.masked.size, traces, dest, note = "stopped after $MAX_ROUNDS tool rounds")
        } catch (t: Throwable) {
            Log.w(CarMemoryService.TAG, "chat failed", t)
            val r = offlineAnswer(packet)
            return AssistantResult(r, "", rendered, packet.masked.size, traces, dest, note = "model failed: ${t.message?.take(200)}; answered from the packet")
        }
    }

    /**
     * Execute a memory tool on the car. `memory_remember` logs now and extracts in the background, like [ask];
     * the model often asks to remember the driver's own words, which [ask] already logged, so that is not logged twice.
     */
    private fun runTool(name: String, args: JsonObject, now: LocalDateTime, said: String, heard: JsonObject): JsonObject = try {
        if (name == "memory_remember") {
            val text = args["text"]?.jsonPrimitive?.contentOrNull ?: ""
            if (tokens(text) == tokens(said)) return heard
            val r = memory.hear(text, null, now, extract = false)
            if (r["deferred"]?.jsonPrimitive?.booleanOrNull == true)
                deferExtraction(r["episode"]!!.jsonPrimitive.longOrNull!!, r["scope"]!!.jsonPrimitive.content, text, now)
            r
        } else memory.handleTool(name, args)
    } catch (t: Throwable) {
        buildJsonObject { put("error", t.message ?: t.javaClass.simpleName) }
    }

    /** Rule answers for driving-time intents; null when the model should answer. */
    private fun intentReply(intent: String, heard: JsonObject, text: String): String? = when (intent) {
        "reject" -> if ((heard["correction"] as? JsonObject)?.get("applied")?.jsonPrimitive?.booleanOrNull == true) "Okay, skipped." else "Okay."
        "accept" -> if ((heard["correction"] as? JsonObject)?.get("applied")?.jsonPrimitive?.booleanOrNull == true) "Okay, doing it." else "Okay."
        "forget" -> forgetReply(heard["forget"] as? JsonObject, text)
        "explain" -> explainReply(text, heard["explanation"] as? JsonObject)
        else -> null
    }

    private fun forgetReply(r: JsonObject?, text: String): String {
        val what = r?.get("forgot")?.jsonPrimitive?.contentOrNull
        if (what != null) return "Forgotten: ${r["description"]?.jsonPrimitive?.contentOrNull ?: what}. It won't come back."
        // Already forgotten earlier (the seed fixture forgets the gym in week 5): say so instead of "nothing found".
        val words = tokens(text) - setOf("forget", "that", "about", "the", "my", "i", "go", "to")
        val scope = memory.occupancy?.driverId
        val tomb = memory.store.tombstones(scope).map { it["signature"] as String }.firstOrNull { sig -> words.any { it.length >= 3 && it in sig.lowercase() } }
        if (tomb != null) return "That's already forgotten (${tomb.substringAfter(':')}), and it stays that way."
        return "I don't have anything like that to forget."
    }

    /**
     * "why do you think I like 21 degrees?" names its subject, so explain the best matching fact; a bare
     * "why?" explains what the core picked (the pending suggestion or the last stated fact).
     */
    private fun explainReply(text: String, fromHear: JsonObject?): String {
        val subject = text.replace(WHY_PREFIX, "").trim(' ', '?', '.', '!')
        var explanation: JsonObject? = null
        if (tokens(subject).isNotEmpty()) {
            val want = tokens(subject).flatMap { SYNONYMS[it] ?: listOf(it) }.filter { it.length >= 2 && it !in STOP }.toSet()
            val recall = memory.recall(subject, 6)
            val facts = (recall["facts"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
            val routines = (recall["routines"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
            val best = (facts + routines).maxByOrNull { o -> (tokens(o["text"]?.jsonPrimitive?.contentOrNull ?: "") intersect want).size }
            if (best != null && (tokens(best["text"]?.jsonPrimitive?.contentOrNull ?: "") intersect want).isNotEmpty())
                explanation = memory.explain(best["id"]!!.jsonPrimitive.content)
        }
        val e = explanation ?: fromHear
        if (e == null || e["record"] is JsonNull || e["record"] == null) return "I don't have a memory about that."
        val because = (e["because"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        val desc = e["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val source = e["source"]?.jsonPrimitive?.contentOrNull ?: ""
        val latest = because.takeLast(2).joinToString("; ") { ev ->
            "${ev["ts"]?.jsonPrimitive?.contentOrNull?.take(10)} ${ev["episode"]?.jsonPrimitive?.contentOrNull ?: ""}".trim()
        }
        val how = if (source == "stated") "you told me" else "from ${because.size} observation(s)"
        return "${pretty(desc)}: $how${if (latest.isNotEmpty()) ", most recently $latest" else ""}."
    }

    /** No model: say the top facts and routines from the packet so the demo degrades gracefully. */
    private fun offlineAnswer(packet: ContextPacket): String {
        val facts = packet.facts.take(3).map { f -> pretty(f.describe()) }
        val routines = packet.routines.take(2).map { it.describe() }
        val all = facts + routines
        return if (all.isEmpty()) "I don't know anything about you yet." else "From memory: ${all.joinToString("; ")}."
    }

    companion object {
        const val MAX_ROUNDS = 4
        const val SYSTEM_PROMPT = "You are the in-car assistant. The driver is driving; answer in one or two short sentences. " +
            "Use the memory tools when the answer depends on history. Never read out record ids."
        private val WHY_PREFIX = Regex("""^\s*why (do|did|would) you (think|say|assume|suggest)( that)?""", RegexOption.IGNORE_CASE)
        private val STOP = setOf("i", "me", "my", "you", "the", "a", "an", "to", "of", "is", "it", "like", "prefer", "want", "do", "that")
        private val SYNONYMS = mapOf(
            "degrees" to listOf("cabin", "temp", "climate"), "temperature" to listOf("cabin", "temp", "climate"),
            "warm" to listOf("cabin", "temp"), "cold" to listOf("cabin", "temp"), "school" to listOf("school"),
            "tolls" to listOf("tolls", "toll"), "toll" to listOf("tolls", "toll"),
        )

        fun tokens(s: String): Set<String> = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

        /** "climate/cabin_temp = 21.0" -> "cabin temp 21.0". */
        fun pretty(describe: String): String = describe.substringAfter('/').replace(" = ", " ").replace('_', ' ')

        /** Replace every masked original with its token. Same substitution the packet applies to its own text. */
        fun mask(text: String, packet: ContextPacket): String {
            var t = text
            packet.masked.forEach { (token, original) -> if (original.isNotEmpty()) t = t.replace(original, token) }
            return t
        }

        /** [mask] applied to every string in a JSON tree (keys stay). Walking the tree avoids breaking JSON escapes. */
        fun maskJson(e: JsonElement, packet: ContextPacket): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.mapValues { (_, v) -> maskJson(v, packet) })
            is JsonArray -> JsonArray(e.map { maskJson(it, packet) })
            is JsonPrimitive -> if (e.isString) JsonPrimitive(mask(e.content, packet)) else e
            else -> e
        }

        /** The cloud model only knows tokens; turn them back into originals before a tool runs on the car. */
        fun unmaskArgs(args: JsonObject, packet: ContextPacket): JsonObject =
            JsonObject(args.mapValues { (_, v) -> if (v is JsonPrimitive && v.isString) JsonPrimitive(packet.unmask(v.content)) else v })
    }
}
