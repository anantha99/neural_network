package carmem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gemma on the Gemini API behind LlmClient, with the schema enforced server-side (responseJsonSchema).
 * Test-only: the shipped path is on-device LiteRT-LM, and this sends raw utterances to Google.
 */
class GeminiLlmClient(private val apiKey: String, private val model: String) : LlmClient {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    val failures = mutableListOf<String>()
    var calls = 0

    override fun completeJson(system: String, user: String, schemaJson: String): String {
        calls++
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") { add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", user) }) } }) }
            putJsonObject("generationConfig") {
                put("temperature", 0.0); put("responseMimeType", "application/json")
                put("responseJsonSchema", Json.parseToJsonElement(schemaJson))
            }
        }
        val req = HttpRequest.newBuilder(URI("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"))
            .header("x-goog-api-key", apiKey).header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(120)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        // the free tier returns sporadic 500s and 429s; retry those twice, as GeminiLlmClient on Android does
        var res = http.send(req, HttpResponse.BodyHandlers.ofString())
        for (attempt in 1..2) {
            if (res.statusCode() != 429 && res.statusCode() < 500) break
            Thread.sleep(1_000L * attempt); res = http.send(req, HttpResponse.BodyHandlers.ofString())
        }
        if (res.statusCode() != 200) { failures += "HTTP ${res.statusCode()}: ${res.body().take(300)}"; error(failures.last()) }
        // Gemma 4 thinks first; the answer is the non-thought part.
        val parts = Json.parseToJsonElement(res.body()).jsonObject["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
        val answer = parts.map { it.jsonObject }.filter { it["thought"]?.jsonPrimitive?.content != "true" }.joinToString("") { it["text"]!!.jsonPrimitive.content }
        println("  [$model] ${user.replace("\n", " | ").take(80)}\n    -> $answer")
        runCatching { Json.parseToJsonElement(answer) }.onFailure { failures += "not JSON: $answer" }
        return answer
    }
}

class LiveLlmTest {
    private val key = System.getenv("GEMINI_API_KEY")
    private val model = System.getenv("CARMEM_LLM_MODEL") ?: "gemma-4-26b-a4b-it"

    private fun client(): GeminiLlmClient { assumeTrue(!key.isNullOrBlank(), "GEMINI_API_KEY not set"); return GeminiLlmClient(key!!, model) }

    @Test fun `extraction through gemma matches the schema and the obvious facts`() {
        val llm = client(); val ex = LlmExtractor(llm)
        val c1 = ex.extract("I like the cabin at 21.5 degrees in the mornings")
        assertTrue(c1.any { it.category == "climate" && it.key == "cabin_temp" && it.value == JsonPrimitive(21.5) }, "climate: $c1")
        val c2 = ex.extract("My daughter's school is Oakwood Primary")
        assertTrue(c2.any { it.category == "school" && it.value.toString().contains("Oakwood", true) }, "school: $c2")
        val c3 = ex.extract("The left rear parking sensor keeps beeping for no reason")
        assertTrue(c3.any { it.category == "vehicle_fault" && it.about == "vehicle" }, "fault: $c3")
        val c4 = ex.extract("What's the weather like today?")
        assertTrue(c4.isEmpty(), "nothing durable: $c4")
        // Something the rules have no pattern for: proves the model, not the fallback, did the work.
        val c5 = ex.extract("I can't stand country music, never play it")
        assertTrue(c5.any { it.category == "media" }, "media: $c5")
        assertEquals(emptyList(), llm.failures.filterNot { it.startsWith("HTTP 5") }) // server errors fall back to the rules by design
    }

    @Test fun `end to end memory with gemma extraction and reconciliation`() {
        val llm = client()
        val mem = CarMemory(JdbcSqlDriver(), llm = llm).also { it.enroll("asha", "Asha", listOf("key_fob" to "fob-a")) }
        val t = LocalDateTime.of(2026, 11, 2, 7, 30)
        mem.ignitionOn(listOf(IdentitySignal("key_fob", "fob-a")), t)
        for (u in listOf(
            "I prefer it at 22 degrees",
            "I cant stand country music, never play it",
            // phrased as on the emulator run, where the model gave the two notes different keys and added a second one
            "remember that Leos piano lesson is on Tuesdays at 5",
            "remember that Leos piano lesson moved to Wednesdays at 5",
            "I'm allergic to peanuts",
        )) println("hear '$u' -> ${mem.hear(u, now = t)["stored"]}")
        val facts = mem.store.facts(listOf("asha")).filter { it.isCurrent }
        facts.forEach { println("  fact ${it.describe()} [${it.state.wire}, ${it.source}]") }
        assertTrue(facts.any { it.category == "climate" && it.key == "cabin_temp" && it.value == JsonPrimitive(22.0) })
        val props = mem.proactive(t, "home")
        props.forEach { println("  proposal ${it.tier.wire}: ${it.text}") }
        assertTrue(props.all { it.kind != "fact" || it.action["key"] == JsonPrimitive("cabin_temp") }, "only cabin_temp is a setting here: $props")
        assertTrue(facts.any { it.category == "health" })
        val piano = facts.filter { it.category == "note" && "piano" in it.key }
        assertEquals(1, piano.size, "the move should update the lesson, not add a second one: $piano")
        assertTrue(piano[0].value.toString().contains("Wednesday", true))
        assertEquals(emptyList(), llm.failures.filterNot { it.startsWith("HTTP 5") }) // server errors fall back to the rules by design
        println("model calls: ${llm.calls}")
    }
}
