package carmem.android

import carmem.LlmClient
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * [LlmClient] over LiteRT-LM (Gemma 3n E2B by default) with JSON-schema constrained decoding,
 * so extraction and note reconciliation get output that parses against their schema.
 *
 * Calls block: run them on the service's worker thread, never the main thread. The engine is
 * initialized on the first call (it can take ~10 s), which is off the critical path because the
 * utterance is logged before extraction. A failure here surfaces as an exception and the core falls
 * back to the rule extractor / rule reconciler.
 */
class LiteRtLmClient(
    modelPath: String,
    cacheDir: String? = null,
    backend: Backend = Backend.CPU(),
    private val maxOutputTokens: Int = 512,
) : LlmClient, AutoCloseable {
    private val engine = Engine(EngineConfig(modelPath = modelPath, backend = backend, cacheDir = cacheDir))

    // Greedy: extraction is a classification task, and the same utterance should give the same memory.
    private val sampler = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)

    @Synchronized
    override fun completeJson(system: String, user: String, schemaJson: String): String {
        if (!engine.isInitialized()) engine.initialize()
        // A conversation keeps history and has no reset, so each call gets a fresh one.
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = sampler,
                maxOutputToken = maxOutputTokens,
                enableResponseFormat = true,
            )
        )
        try {
            return conversation.sendMessage(user, responseFormat = ResponseFormat.json(schemaJson)).toString()
        } finally {
            conversation.close()
        }
    }

    @Synchronized
    override fun close() = engine.close()
}
