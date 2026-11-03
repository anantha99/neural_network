package carmem

data class Decision(val op: String, val target: Fact?, val similarity: Double, val by: String)

/** Mem0-style add / update / noop for free-text memories. Rules by default; an LLM may decide when present. */
class Reconciler(private val policy: Policy, private val embedder: Embedder, private val llm: LlmClient? = null) {
    private val schema = """{"type":"object","properties":{"op":{"type":"string","enum":["add","update","noop"]},"target_index":{"type":["integer","null"]}},"required":["op","target_index"],"additionalProperties":false}"""
    private val system = "You maintain a driver's memory. Given a NEW statement and a numbered list of EXISTING memories, decide: 'noop' if an existing memory already says the same thing (target_index = that memory); 'update' if an existing memory is about the same subject but the new statement corrects or refreshes it (target_index = that memory); otherwise 'add' (target_index = null)."

    /** newKey is the subject the extractor gave the new statement; the model sees key and value, the rules values only. */
    fun decide(newText: String, existing: List<Fact>, newKey: String? = null): Decision {
        if (existing.isEmpty()) return Decision("add", null, 0.0, "rules")
        val scored = existing.map { similarity(newText, it.value.render(), embedder) to it }.sortedByDescending { it.first }.take(policy.reconcile_top_k)
        if (llm != null) try {
            val listing = scored.mapIndexed { i, (_, f) -> "$i. ${f.key.replace('_', ' ')}: ${f.value.render()}" }.joinToString("\n")
            val new = if (newKey != null) "${newKey.replace('_', ' ')}: $newText" else newText
            val out = kotlinx.serialization.json.Json.parseToJsonElement(llm.completeJson(system, "NEW: $new\nEXISTING:\n$listing", schema)) as kotlinx.serialization.json.JsonObject
            val op = (out["op"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "add"
            val idx = (out["target_index"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            if (op in setOf("update", "noop") && idx != null && idx in scored.indices) return Decision(op, scored[idx].second, scored[idx].first, "llm")
            if (op == "add") return Decision("add", null, scored[0].first, "llm")
        } catch (_: Exception) { }
        val (s, f) = scored[0]
        return when {
            s >= policy.reconcile_noop_similarity -> Decision("noop", f, s, "rules")
            s >= policy.reconcile_update_similarity -> Decision("update", f, s, "rules")
            else -> Decision("add", null, s, "rules")
        }
    }
}
