package carmem.car

import java.time.Duration
import java.time.LocalDateTime

/**
 * A clock for demos: pass it as `CarMemory(clock = demoClock)` and every call without
 * an explicit timestamp sees the pinned time. After [set], time advances in real time
 * from the pinned instant; [advance] jumps ahead; [reset] returns to the wall clock.
 * Thread-safe.
 */
class DemoClock : () -> LocalDateTime {
    private var pinned: LocalDateTime? = null
    private var pinnedAtNanos = 0L

    @Synchronized override fun invoke(): LocalDateTime =
        pinned?.plusNanos(System.nanoTime() - pinnedAtNanos) ?: LocalDateTime.now()

    /** Pin "now" to `t`; later calls advance in real time from here. */
    @Synchronized fun set(t: LocalDateTime) { pinned = t; pinnedAtNanos = System.nanoTime() }

    /** Jump ahead (or back, with a negative duration). Pins the clock if it was on wall time. */
    @Synchronized fun advance(d: Duration) { set(invoke().plus(d)) }

    /** Back to the wall clock. */
    @Synchronized fun reset() { pinned = null }

    val isPinned: Boolean @Synchronized get() = pinned != null
}
