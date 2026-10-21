package tw.personal.privateinbox

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Quantitative capture-pipeline metrics.
 *
 * This exists to answer the question "where did a message go?" with numbers
 * instead of guesses. It is deliberately SEPARATE from
 * [CaptureDiagnostics]:
 *
 *  - [CaptureDiagnostics] is a bounded RING BUFFER of events, for reading the
 *    story of one capture attempt.
 *  - This is COUNTERS and DISTRIBUTIONS, for comparing runs.
 *
 * **Privacy.** Nothing here identifies a message, a contact or a conversation.
 * The only per-event identifier is an opaque, non-reversible hash of the
 * notification key, so that two events can be correlated with each other
 * without revealing what the notification contained. The package name IS
 * recorded, because it is the one thing a support report legitimately needs.
 *
 * The metrics are in-memory. They are lost when the process dies, which is the
 * documented limitation: a process death between the callback and the database
 * write is not observable after the fact.
 */
object CaptureMetrics {

    // ---- counters -------------------------------------------------------

    /** Times the listener callback was entered. */
    val callbacksEntered = AtomicInteger(0)

    /** Times the listener callback completed. */
    val callbacksExited = AtomicInteger(0)

    /** Times a notification was accepted by the source policy. */
    val policyAccepted = AtomicInteger(0)

    /** Times a notification was rejected by the source policy. */
    val policyRejected = AtomicInteger(0)

    /** Times a notification was parsed into at least one message. */
    val parseSucceeded = AtomicInteger(0)

    /** Times a notification could not be parsed. */
    val parseFailed = AtomicInteger(0)

    /**
     * How many notifications the source posted.
     *
     * This is the test's own count, not the app's: the app cannot know how
     * many times a source called notify().
     */
    val notificationAttempts = AtomicInteger(0)

    /**
     * The messages PRESENTED in the parsed notifications.
     *
     * This counts every message the source showed, including the ones that
     * were already captured in an earlier revision. It is the "logical
     * messages exposed to the listener" denominator, and it is deliberately
     * LARGER than the number of writes: a group notification presents its
     * whole history every time.
     */
    val messagesPresented = AtomicInteger(0)

    /**
     * The messages SUBMITTED for writing.
     *
     * This is the number that was new in its revision and therefore had to be
     * written. It is the denominator the database accounting has to add up
     * against: submitted == saved + deduplicated + failed.
     */
    val messagesSubmitted = AtomicInteger(0)

    /** Tasks accepted by the work queue. */
    val queued = AtomicInteger(0)

    /** Tasks REJECTED because the queue was full. */
    val queueOverflow = AtomicInteger(0)

    /** Tasks that started running on the worker. */
    val dequeued = AtomicInteger(0)

    /** The deepest the queue has ever been. */
    val queueHighWatermark = AtomicInteger(0)

    /** The current queue depth. */
    val queueDepth = AtomicInteger(0)

    /** Database inserts that produced a new row. */
    val dbCommitted = AtomicInteger(0)

    /** Database inserts that were recognised as duplicates. */
    val dbDuplicated = AtomicInteger(0)

    /** Database writes that failed. */
    val dbFailed = AtomicInteger(0)

    /** Notifications that carried only a group summary. */
    val groupSummarySkipped = AtomicInteger(0)

    /** Messages dropped because the safety bound was reached. */
    val messagesTruncated = AtomicInteger(0)

    /** The worker caught a throwable and kept running. */
    val workerFailures = AtomicInteger(0)

    // ---- distributions --------------------------------------------------

    /** The callback's wall time, in milliseconds. */
    val callbackDuration = LatencyDistribution("callback_ms")

    /** The time a task waited in the queue, in milliseconds. */
    val queueWait = LatencyDistribution("queue_wait_ms")

    /** The database write, in milliseconds. */
    val dbDuration = LatencyDistribution("db_ms")

    /** The time from the callback entering to the row being committed. */
    val endToEnd = LatencyDistribution("end_to_end_ms")

    /**
     * A bounded latency sample.
     *
     * The samples are kept in a fixed-size ring so the memory cost cannot
     * grow without limit during a sustained flood. The percentiles are
     * therefore computed over the most recent samples, which is what matters
     * for a burst.
     */
    class LatencyDistribution(val name: String) {
        private val capacity = 4096
        private val samples = LongArray(capacity)
        private val count = AtomicInteger(0)
        private val index = AtomicInteger(0)
        private val sum = AtomicLong(0)
        private val max = AtomicLong(0)

        @Synchronized
        fun record(valueMs: Long) {
            if (valueMs < 0) return
            samples[index.getAndIncrement() % capacity] = valueMs
            count.incrementAndGet()
            sum.addAndGet(valueMs)
            var observed = max.get()
            while (valueMs > observed && !max.compareAndSet(observed, valueMs)) {
                observed = max.get()
            }
        }

        @Synchronized
        fun snapshot(): Percentiles {
            val n = count.get().coerceAtMost(capacity)
            if (n == 0) return Percentiles(name, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val sorted = samples.copyOf(n).also { it.sort() }
            val total = sum.get()
            return Percentiles(
                name = name,
                count = n,
                p50 = percentile(sorted, 0.50),
                p95 = percentile(sorted, 0.95),
                p99 = percentile(sorted, 0.99),
                max = sorted.last().toDouble(),
                mean = total.toDouble() / n
            )
        }

        private fun percentile(sorted: LongArray, q: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val index = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index].toDouble()
        }

        @Synchronized
        fun reset() {
            count.set(0); index.set(0); sum.set(0); max.set(0)
        }
    }

    /** A latency summary. */
    data class Percentiles(
        val name: String,
        val count: Int,
        val p50: Double,
        val p95: Double,
        val p99: Double,
        val max: Double,
        val mean: Double
    ) {
        override fun toString(): String =
            String.format("%s n=%d p50=%.1f p95=%.1f p99=%.1f max=%.0f mean=%.1f",
                name, count, p50, p95, p99, max, mean)
    }

    /**
     * An opaque, non-reversible identifier for a notification.
     *
     * The notification key contains the package name and the source app's
     * internal ids. Those are not private content, but they are also not
     * needed to correlate events, so only a salted digest is kept. The salt is
     * per-process, so the value cannot be correlated across runs.
     */
    private val salt: AtomicReference<String> = AtomicReference(
        ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { byte: Byte -> "%02x".format(byte.toInt() and 0xff) }
    )

    fun opaqueId(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt.get()\u0000$value".toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }

    /** Record the current queue depth and update the high watermark. */
    fun observeQueueDepth(depth: Int) {
        queueDepth.set(depth)
        var observed = queueHighWatermark.get()
        while (depth > observed && !queueHighWatermark.compareAndSet(observed, depth)) {
            observed = queueHighWatermark.get()
        }
    }

    /** Reset every counter and distribution. Used between test runs. */
    fun reset() {
        callbacksEntered.set(0); callbacksExited.set(0)
        policyAccepted.set(0); policyRejected.set(0)
        parseSucceeded.set(0); parseFailed.set(0)
        notificationAttempts.set(0)
        messagesPresented.set(0); messagesSubmitted.set(0)
        queued.set(0); queueOverflow.set(0); dequeued.set(0)
        queueHighWatermark.set(0); queueDepth.set(0)
        dbCommitted.set(0); dbDuplicated.set(0); dbFailed.set(0)
        groupSummarySkipped.set(0); messagesTruncated.set(0); workerFailures.set(0)
        callbackDuration.reset(); queueWait.reset(); dbDuration.reset(); endToEnd.reset()
    }

    /**
     * A machine-readable snapshot of every counter.
     *
     * The keys are stable so the report can be compared across runs.
     */
    fun snapshot(): Map<String, Long> = linkedMapOf(
        "callbacks_entered" to callbacksEntered.get().toLong(),
        "callbacks_exited" to callbacksExited.get().toLong(),
        "policy_accepted" to policyAccepted.get().toLong(),
        "policy_rejected" to policyRejected.get().toLong(),
        "parse_succeeded" to parseSucceeded.get().toLong(),
        "parse_failed" to parseFailed.get().toLong(),
        "notification_attempts" to notificationAttempts.get().toLong(),
        "messages_presented" to messagesPresented.get().toLong(),
        "messages_submitted" to messagesSubmitted.get().toLong(),
        "queued" to queued.get().toLong(),
        "queue_overflow" to queueOverflow.get().toLong(),
        "dequeued" to dequeued.get().toLong(),
        "queue_depth" to queueDepth.get().toLong(),
        "queue_high_watermark" to queueHighWatermark.get().toLong(),
        "db_committed" to dbCommitted.get().toLong(),
        "db_duplicated" to dbDuplicated.get().toLong(),
        "db_failed" to dbFailed.get().toLong(),
        "group_summary_skipped" to groupSummarySkipped.get().toLong(),
        "messages_truncated" to messagesTruncated.get().toLong(),
        "worker_failures" to workerFailures.get().toLong()
    )

    /** A human-readable one-line summary of the latency distributions. */
    fun latencySummary(): String = listOf(
        callbackDuration, queueWait, dbDuration, endToEnd
    ).joinToString("  ") { it.snapshot().toString() }
}
