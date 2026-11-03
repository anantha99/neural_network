package carmem.car

import carmem.CarMemory
import kotlinx.serialization.json.JsonArray
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs utterance extraction off the memory thread.
 *
 * `hear(..., extract = false)` logs the utterance and handles intents immediately; the
 * slow part (the model call, [CarMemory.extractCandidates]) runs here on one background
 * thread, in submission order. Its result is handed to [runOnMemoryThread], which must run
 * it on the thread that owns the [CarMemory] (on Android: post to the service's worker
 * Handler), where [CarMemory.applyCandidates] writes it. The default runs it inline on the
 * queue thread, which is only safe when nothing else touches the memory meanwhile.
 */
class ExtractionQueue(
    private val memory: CarMemory,
    private val runOnMemoryThread: (() -> Unit) -> Unit = { it() },
    private val onApplied: (episodeId: Long, stored: JsonArray) -> Unit = { _, _ -> },
    private val onError: (episodeId: Long, error: Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "carmem-extraction").apply { isDaemon = true } }
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var outstanding = 0

    /** Queue extraction for an utterance already logged as episode `episodeId` in `scope`. */
    fun submit(episodeId: Long, scope: String, text: String, now: LocalDateTime) {
        lock.withLock { outstanding++ }
        try {
            executor.execute {
                try {
                    val candidates = memory.extractCandidates(text)
                    runOnMemoryThread {
                        try { onApplied(episodeId, memory.applyCandidates(episodeId, scope, candidates, now)) }
                        catch (t: Throwable) { onError(episodeId, t) }
                    }
                } catch (t: Throwable) {
                    onError(episodeId, t)
                } finally { done() }
            }
        } catch (t: Throwable) { done(); throw t }
    }

    private fun done() = lock.withLock { outstanding--; if (outstanding == 0) idle.signalAll() }

    /** Jobs submitted but not yet handed to [runOnMemoryThread]. */
    val pending: Int get() = lock.withLock { outstanding }

    /**
     * Block until every submitted job has been extracted and handed to [runOnMemoryThread]
     * (with the inline default, also applied). For tests and shutdown; never call it from the
     * memory thread when [runOnMemoryThread] posts to that same thread. Returns false on timeout.
     */
    fun drain(timeout: Duration = Duration.ofMinutes(5)): Boolean = lock.withLock {
        var left = timeout.toNanos()
        while (outstanding > 0) { if (left <= 0) return false; left = idle.awaitNanos(left) }
        true
    }

    /** Stop the thread. Jobs not yet started are dropped; their utterances stay logged as episodes. */
    override fun close() {
        val dropped = executor.shutdownNow()
        lock.withLock { outstanding -= dropped.size; idle.signalAll() }
        executor.awaitTermination(1, TimeUnit.SECONDS)
    }
}
