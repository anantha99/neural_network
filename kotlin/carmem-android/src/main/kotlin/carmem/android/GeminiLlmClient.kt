package carmem.android

import android.util.Log
import carmem.LlmClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Development stand-in for the on-device model: Gemma on the Gemini API, schema enforced
 * server-side. It sends raw utterances to Google, so it is only used when a key has been
 * pushed to the service's noBackupFilesDir, and the host must hold INTERNET.
 */
class GeminiLlmClient(private val apiKey: String, private val model: String = DEFAULT_MODEL) : LlmClient {
    override fun completeJson(system: String, user: String, schemaJson: String): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            putJsonArray("contents") { add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", user) }) } }) }
            putJsonObject("generationConfig") {
                put("temperature", 0.0); put("responseMimeType", "application/json")
                put("responseJsonSchema", Json.parseToJsonElement(schemaJson))
            }
        }
        val started = System.currentTimeMillis()
        try {
            val res = geminiPost(apiKey, model, body.toString())
            // Gemma 4 thinks first; the answer is the non-thought part.
            val parts = Json.parseToJsonElement(res).jsonObject["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            val answer = parts.map { it.jsonObject }.filter { it["thought"]?.jsonPrimitive?.content != "true" }
                .joinToString("") { it["text"]!!.jsonPrimitive.content }
            Log.i(CarMemoryService.TAG, "llm $model ${System.currentTimeMillis() - started} ms: $answer")
            return answer
        } catch (e: Exception) {
            Log.w(CarMemoryService.TAG, "llm $model failed after ${System.currentTimeMillis() - started} ms; rules take over", e)
            throw e
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemma-4-26b-a4b-it"
        /** API key file in noBackupFilesDir; optional second line names the model. */
        const val KEY_FILE = "gemini.key"
    }
}

/**
 * POST a generateContent request to the Gemini API and return the response body. Shared by
 * [GeminiLlmClient] (extraction) and [GeminiChatModel] (the assistant loop). Blocks: never call on the
 * main thread. The free tier returns sporadic 500s and 429s; retry those [retries] times with a short backoff.
 */
internal fun geminiPost(apiKey: String, model: String, body: String, retries: Int = 2): String {
    for (attempt in 0..retries) {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 20_000; conn.readTimeout = 120_000
            conn.setRequestProperty("x-goog-api-key", apiKey); conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code == 200) return conn.inputStream.bufferedReader().readText()
            val err = "HTTP $code: ${conn.errorStream?.bufferedReader()?.readText()?.take(300)}"
            if (code != 429 && code < 500 || attempt == retries) error(err)
            Log.w(CarMemoryService.TAG, "llm $model $err; retrying")
        } finally {
            conn.disconnect()
        }
        Thread.sleep(1_000L * (attempt + 1))
    }
    error("unreachable")
}
