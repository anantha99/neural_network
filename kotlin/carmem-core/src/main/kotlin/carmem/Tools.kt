package carmem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Neutral tool definitions (same six as carmem/llm/tools.py) plus Anthropic / OpenAI / LiteRT-LM shapes. */
object MemoryTools {
    private fun params(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }
    private fun str(desc: String? = null) = buildJsonObject { put("type", "string"); desc?.let { put("description", it) } }

    val neutral: List<JsonObject> = listOf(
        buildJsonObject { put("name", "memory_recall"); put("description", "Search the driver's memory (facts, routines, recent trips, vehicle notes) for the current occupant only."); put("parameters", params("query" to str(), "k" to buildJsonObject { put("type", "integer") }, required = listOf("query"))) },
        buildJsonObject { put("name", "memory_remember"); put("description", "Store something the driver just said that should persist across trips."); put("parameters", params("text" to str("The utterance, verbatim."), required = listOf("text"))) },
        buildJsonObject { put("name", "memory_forget"); put("description", "Delete a memory the driver wants gone. Pass the record id, or a short description to search."); put("parameters", params("target" to str(), required = listOf("target"))) },
        buildJsonObject { put("name", "memory_correct"); put("description", "Driver answered a proactive suggestion. answer='no' demotes it, 'yes' confirms it."); put("parameters", params("answer" to buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("yes"), JsonPrimitive("no")))) }, required = listOf("answer"))) },
        buildJsonObject { put("name", "memory_explain"); put("description", "Show the evidence behind a memory record so the driver can audit it."); put("parameters", params("record_id" to str(), required = listOf("record_id"))) },
        buildJsonObject { put("name", "memory_proactive"); put("description", "What the car would do right now on its own, with the tier that governs each item."); put("parameters", params("location" to str("Where the car is now, if known."))) },
    )

    fun anthropic(): List<JsonObject> = neutral.map { t -> buildJsonObject { put("name", t["name"]!!); put("description", t["description"]!!); put("input_schema", t["parameters"]!!); put("strict", true) } }
    fun openai(): List<JsonObject> = neutral.map { t -> buildJsonObject { put("type", "function"); put("function", buildJsonObject { put("name", t["name"]!!); put("description", t["description"]!!); put("parameters", t["parameters"]!!); put("strict", true) }) } }
    fun json(tools: List<JsonObject>): String = Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(tools))
}
