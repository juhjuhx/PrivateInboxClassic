package tw.personal.privateinbox

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pipeline latency stages.
 *
 * The brief is explicit that the enqueue time must NOT be treated as the
 * durability time, so the stages are measured separately and each one has its
 * own name:
 *
 *   T0  the callback is entered
 *   T1  the necessary snapshot is complete
 *   T2  the enqueue succeeded
 *   T3  the worker started on it
 *   T4  the dedup and parse are done
 *   T5  the SQLite commit is done
 *   T6  the message is visible to the UI
 *
 *   snapshot_latency  = T1 - T0
 *   enqueue_latency   = T2 - T0
 *   queue_wait        = T3 - T2
 *   processing        = T5 - T3
 *   durable_latency   = T5 - T0
 *   visible_latency   = T6 - T0
 *
 * The clock is [SystemClock.elapsedRealtimeNanos], which is MONOTONIC: it
 * cannot jump backwards the way `currentTimeMillis` can when the user changes
 * the clock. A latency measured with a wall clock is not a latency.
 *
 * The samples are bounded, so a sustained flood cannot grow the heap.
 */
object PipelineLatency {

    /** The stages, in the order they happen. */
    enum class Stage {
        /** T1: the snapshot is complete. */
        SNAPSHOT,

        /** T2: the enqueue succeeded. */
        ENQUEUE,

        /** T3: the worker started on the task. */
        WORKER_START,

        /** T4: the dedup and the parse are done. */
        PROCESSED,

        /** T5: the SQLite commit is done. */
        COMMITTED,

        /** T6: the message is visible to the UI. */
        VISIBLE
    }

    /** The number of samples kept per stage. */
    private const val CAPACITY = 4096

    private val samples = HashMap<Stage, LongArray>()
    private val counts = HashMap<Stage, AtomicInteger>()

    @Synchronized
    fun record(stage: Stage, nanos: Long) {
        if (nanos < 0) return
        val array = samples.getOrPut(stage) { LongArray(CAPACITY) }
        val counter = counts.getOrPut(stage) { AtomicInteger(0) }
        val index = counter.getAndIncrement() % CAPACITY
        array[index] = nanos
    }

    /** Record the interval between two points, in nanoseconds. */
    @Synchronized
    fun since(stage: Stage, startNanos: Long): Long {
        val elapsed = SystemClock.elapsedRealtimeNanos() - startNanos
        record(stage, elapsed)
        return elapsed
    }

    /** The samples recorded for a stage, sorted. */
    @Synchronized
    fun samplesOf(stage: Stage): LongArray {
        val array = samples[stage] ?: return LongArray(0)
        val count = (counts[stage]?.get() ?: 0).coerceAtMost(CAPACITY)
        return array.copyOf(count).also { it.sort() }
    }

    /** The percentile of a stage, in nanoseconds. */
    fun percentile(stage: Stage, q: Double): Double {
        val sorted = samplesOf(stage)
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index].toDouble()
    }

    /** How many samples a stage has. */
    fun countOf(stage: Stage): Int = samplesOf(stage).size

    /** The maximum of a stage, in nanoseconds. */
    fun maxOf(stage: Stage): Double {
        val sorted = samplesOf(stage)
        return if (sorted.isEmpty()) 0.0 else sorted.last().toDouble()
    }

    /** Reset every stage. */
    @Synchronized
    fun reset() {
        samples.clear()
        counts.clear()
    }

    /**
     * A one-line report of every stage.
     *
     * The unit is milliseconds, because that is what a person can act on.
     */
    fun report(): String = Stage.entries.joinToString("  ") { stage ->
        val p50 = percentile(stage, 0.50) / 1_000_000.0
        val p95 = percentile(stage, 0.95) / 1_000_000.0
        val p99 = percentile(stage, 0.99) / 1_000_000.0
        val n = countOf(stage)
        String.format("%s n=%d p50=%.2f p95=%.2f p99=%.2f", stage.name.lowercase(), n, p50, p95, p99)
    }

    /**
     * The target table from the brief, and whether each is met.
     *
     * The targets are stated as CHALLENGE targets, so this reports the
     * measurement against them without claiming they are met unless the
     * numbers say so.
     */
    fun targetTable(): String {
        val enqueueP50 = percentile(Stage.ENQUEUE, 0.50) / 1_000_000.0
        val callbackP95 = percentile(Stage.SNAPSHOT, 0.95) / 1_000_000.0
        val queueP95 = percentile(Stage.WORKER_START, 0.95) / 1_000_000.0
        return buildString {
            appendLine("enqueue p50   ${fmt(enqueueP50)} ms  target <= 2      ${verdict(enqueueP50 <= 2.0)}")
            appendLine("callback p95  ${fmt(callbackP95)} ms  target <= 5      ${verdict(callbackP95 <= 5.0)}")
            appendLine("queue wait p95${fmt(queueP95)} ms  target <= 20     ${verdict(queueP95 <= 20.0)}")
        }
    }

    private fun fmt(value: Double): String = String.format("%7.3f", value)

    private fun verdict(met: Boolean): String = if (met) "MET" else "NOT MET"

    /**
     * The context is not used by the latency recorder itself, but the callers
     * hold one, and this keeps the signature uniform with the other metric
     * collectors.
     */
    @Suppress("UNUSED_PARAMETER")
    fun anchor(context: Context): Long = SystemClock.elapsedRealtimeNanos()
}
