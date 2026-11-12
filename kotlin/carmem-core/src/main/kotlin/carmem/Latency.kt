package carmem

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Per-call timing for whoever hosts the memory: the last [window] samples of each named call,
 * reported as count, p50, p95, p99 and max in milliseconds. This is how the latency budget is
 * checked on the target hardware, where the numbers from a build machine do not apply.
 *
 * `total` is what the caller waited; `queued` is the part spent waiting for the memory's single
 * thread, so a slow total with a slow queue means something else was running, not that the call is slow.
 */
class LatencyRecorder(private val window: Int = 512, private val nanoTime: () -> Long = System::nanoTime) {
    private class Series(window: Int) {
        val total = LongArray(window); val queued = LongArray(window)
        var n = 0L; var over = 0L; var worst = 0L
    }
    private val series = linkedMapOf<String, Series>()

    /** A call is over budget when it took longer than this; counted per call in `over_budget`. */
    @Volatile var budgetMs: Double = 50.0

    fun now(): Long = nanoTime()

    /** Record a call that was requested at [requestedAt], started running at [startedAt] and has just finished. */
    @Synchronized fun record(name: String, requestedAt: Long, startedAt: Long) {
        val end = nanoTime()
        val s = series.getOrPut(name) { Series(window) }
        val i = (s.n % window).toInt()
        s.total[i] = end - requestedAt; s.queued[i] = startedAt - requestedAt
        s.n++
        if (end - requestedAt > budgetMs * 1e6) s.over++
        if (end - requestedAt > s.worst) s.worst = end - requestedAt
    }

    inline fun <T> time(name: String, block: () -> T): T {
        val t = now()
        try { return block() } finally { record(name, t, t) }
    }

    @Synchronized fun reset() = series.clear()

    @Synchronized fun toJson(): JsonObject = buildJsonObject {
        put("budget_ms", budgetMs)
        put("calls", buildJsonObject {
            for ((name, s) in series) {
                val k = minOf(s.n, window.toLong()).toInt()
                val total = s.total.copyOf(k).also { it.sort() }; val queued = s.queued.copyOf(k).also { it.sort() }
                fun ms(a: LongArray, q: Double) = if (a.isEmpty()) 0.0 else Math.round(a[minOf(a.size - 1, (q * a.size).toInt())] / 1e4) / 100.0
                put(name, buildJsonObject {
                    put("count", s.n); put("over_budget", s.over)
                    put("p50_ms", ms(total, 0.50)); put("p95_ms", ms(total, 0.95)); put("p99_ms", ms(total, 0.99))
                    put("max_ms", Math.round(s.worst / 1e4) / 100.0); put("queued_p99_ms", ms(queued, 0.99))
                })
            }
        })
    }
}
