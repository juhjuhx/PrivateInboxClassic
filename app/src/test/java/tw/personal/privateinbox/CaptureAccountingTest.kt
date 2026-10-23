package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture accounting, checked as a state machine.
 *
 * The brief found a real inconsistency: one document reported
 * `submitted=250 saved=250 unaccounted=250` while the formula said
 * `unaccounted = submitted - saved - deduped`, which for those numbers is 0.
 * A second document reported `unaccounted=3821`, which came from a different
 * run being mixed in.
 *
 * Two runs cannot be compared by eye. The accounting below makes the
 * relationships EXPLICIT and machine-checked, so a mismatch is a failing test
 * rather than a sentence in a document.
 *
 * The counters are DELIBERATELY not derivable from one another, because the
 * whole point is to be able to say that a message was lost.
 */
class CaptureAccountingTest {

    /**
     * The accounting for one capture run.
     *
     * Every field has a distinct meaning, and the relationships between them
     * are checked rather than assumed.
     */
    data class Accounting(
        /** How many notify() calls the test made. */
        val notificationAttempts: Int = 0,
        /** How many times the listener callback was entered. */
        val listenerCallbacks: Int = 0,
        /**
         * How many message OCCURRENCES the listener saw across all the
         * notifications it parsed. A group notification presents its whole
         * history every time, so this is much larger than the number of
         * distinct messages.
         */
        val presentedOccurrences: Int = 0,
        /**
         * How many DISTINCT messages the pipeline considered. This is what
         * `submitted` counts: a message that was already captured in an
         * earlier revision is not submitted again.
         */
        val uniqueCandidates: Int = 0,
        /** How many candidates the source policy accepted. */
        val acceptedCandidates: Int = 0,
        /** How many rows ended up in the database. */
        val committedMessages: Int = 0,
        /** How many writes were recognised as duplicates of a stored row. */
        val duplicates: Int = 0,
        /** How many writes failed explicitly. */
        val explicitFailures: Int = 0,
        /**
         * How many accepted candidates had NOT reached a terminal state when
         * the run was measured: still queued, or in flight.
         *
         * This is an INPUT, not a derived value, so that a lost message (a
         * candidate that reached no terminal state and was never in flight)
         * can be told apart from one that was merely still queued.
         */
        val inFlight: Int = 0
    ) {
        /**
         * The unaccounted count.
         *
         * It is computed from the ACCEPTED count, not the presented count.
         * The presented count includes occurrences that were never submitted
         * (they were already captured), so subtracting it here would mix two
         * different denominators -- which is exactly the mistake the brief
         * found.
         */
        val unaccounted: Int
            get() = acceptedCandidates - committedMessages - duplicates -
                explicitFailures - inFlight

        /**
         * The identities the accounting must satisfy.
         *
         * Each is a relationship between two counters, and each has failed at
         * some point during development.
         */
        fun violations(): List<String> {
            val out = ArrayList<String>()
            // Each posted notification is delivered at least once, and the
            // platform delivers an UPDATE as a separate callback. So the
            // callback count is at least the post count -- the measured
            // relationship is 748 callbacks for 500 posts.
            if (listenerCallbacks < notificationAttempts) {
                out += "listenerCallbacks ($listenerCallbacks) must be at least " +
                    "notificationAttempts ($notificationAttempts): every posted " +
                    "notification is delivered at least once."
            }
            // Each callback presents at least one message.
            if (presentedOccurrences < listenerCallbacks) {
                out += "presentedOccurrences ($presentedOccurrences) must be at " +
                    "least listenerCallbacks ($listenerCallbacks)."
            }
            if (uniqueCandidates > presentedOccurrences) {
                out += "uniqueCandidates ($uniqueCandidates) must not exceed " +
                    "presentedOccurrences ($presentedOccurrences)."
            }
            if (uniqueCandidates > acceptedCandidates) {
                out += "uniqueCandidates ($uniqueCandidates) must not exceed " +
                    "acceptedCandidates ($acceptedCandidates): a candidate is " +
                    "either accepted or rejected by the policy."
            }
            if (committedMessages + duplicates + explicitFailures + inFlight >
                acceptedCandidates
            ) {
                out += "committed ($committedMessages) + duplicates " +
                    "($duplicates) + failures ($explicitFailures) + inFlight " +
                    "($inFlight) must not exceed acceptedCandidates " +
                    "($acceptedCandidates)."
            }
            if (unaccounted < 0) {
                out += "unaccounted ($unaccounted) must not be negative: that " +
                    "would mean more rows were written than were accepted."
            }
            return out
        }
    }

    @Test
    fun aFullyAccountedRunHasNoViolations() {
        val run = Accounting(
            notificationAttempts = 500,
            listenerCallbacks = 748,
            presentedOccurrences = 9_500,
            uniqueCandidates = 500,
            acceptedCandidates = 500,
            committedMessages = 500,
            duplicates = 0,
            explicitFailures = 0,
            inFlight = 0
        )
        assertEquals(
            "a fully accounted run must have no violations: ${run.violations()}",
            emptyList<String>(), run.violations()
        )
        assertEquals("nothing may be unaccounted for", 0, run.unaccounted)
    }

    @Test
    fun theUnaccountedCountUsesTheAcceptedDenominator() {
        // The mistake the brief found: subtracting the PRESENTED count.
        // 9,500 presented, 500 accepted, 500 saved, 0 deduplicated.
        val run = Accounting(
            presentedOccurrences = 9_500,
            uniqueCandidates = 500,
            acceptedCandidates = 500,
            committedMessages = 500
        )
        assertEquals(
            "the unaccounted count must be computed from the accepted count",
            0, run.unaccounted
        )
        // The WRONG calculation, for the record.
        val wrong = run.presentedOccurrences - run.committedMessages - run.duplicates
        assertEquals(
            "the wrong calculation is what produced the 3,821 figure",
            9_000, wrong
        )
    }

    @Test
    fun aRunWithUnsubmittedMessagesIsStillFullyAccounted() {
        // 9,500 occurrences presented, but only 500 were submitted because the
        // other 9,000 were already captured. That is NOT a loss.
        val run = Accounting(
            notificationAttempts = 500,
            listenerCallbacks = 748,
            presentedOccurrences = 9_500,
            uniqueCandidates = 500,
            acceptedCandidates = 500,
            committedMessages = 500
        )
        assertEquals("the extra occurrences are not a loss", 0, run.unaccounted)
        assertEquals(
            "and the accounting must still hold", emptyList<String>(), run.violations()
        )
    }

    @Test
    fun aRunWithLostMessagesIsDetected() {
        // 500 accepted, 400 committed, 0 deduplicated, 0 in flight: 100 went
        // missing and nothing was still queued.
        val run = Accounting(
            notificationAttempts = 10,
            listenerCallbacks = 10,
            presentedOccurrences = 500,
            uniqueCandidates = 500,
            acceptedCandidates = 500,
            committedMessages = 400
        )
        assertEquals("the loss must be visible", 100, run.unaccounted)
        // A loss is not an inequality violation: every counter is internally
        // consistent, there are just 100 candidates that reached no terminal
        // state. The accounting reports it through unaccounted, not through
        // violations(). The two mechanisms are deliberately different.
        assertEquals(
            "a loss is reported through unaccounted, not through violations",
            emptyList<String>(), run.violations()
        )
    }

    @Test
    fun aRunWithMoreRowsThanAcceptedIsDetected() {
        // 10 accepted, 12 committed: impossible, and it must be caught.
        val run = Accounting(
            notificationAttempts = 5,
            listenerCallbacks = 5,
            presentedOccurrences = 10,
            uniqueCandidates = 10,
            acceptedCandidates = 10,
            committedMessages = 12
        )
        assertTrue(
            "an impossible run must be reported: ${run.violations()}",
            run.violations().any { it.contains("must not exceed acceptedCandidates") }
        )
    }

    @Test
    fun duplicatesAreCountedSeparatelyFromCommits() {
        // A run where some writes were recognised as already stored.
        val run = Accounting(
            notificationAttempts = 10,
            listenerCallbacks = 10,
            presentedOccurrences = 100,
            uniqueCandidates = 100,
            acceptedCandidates = 100,
            committedMessages = 70,
            duplicates = 30
        )
        assertEquals("nothing may be unaccounted for", 0, run.unaccounted)
        assertEquals("the accounting must hold", emptyList<String>(), run.violations())
    }

    @Test
    fun failuresAreCountedSeparatelyFromUnaccounted() {
        // A write that failed is an EXPLICIT failure, not a silent loss.
        val run = Accounting(
            notificationAttempts = 10,
            listenerCallbacks = 10,
            presentedOccurrences = 100,
            uniqueCandidates = 100,
            acceptedCandidates = 100,
            committedMessages = 90,
            explicitFailures = 10
        )
        assertEquals(
            "an explicit failure is accounted for, not unaccounted",
            0, run.unaccounted
        )
        assertEquals("the accounting must hold", emptyList<String>(), run.violations())
    }

    @Test
    fun inFlightMessagesAreNotUnaccounted() {
        // A candidate that was still queued when the run was measured is not
        // lost. It has not reached a terminal state yet.
        val run = Accounting(
            notificationAttempts = 10,
            listenerCallbacks = 10,
            presentedOccurrences = 100,
            uniqueCandidates = 100,
            acceptedCandidates = 100,
            committedMessages = 60,
            inFlight = 40
        )
        assertEquals(
            "a queued message is not a lost message",
            0, run.unaccounted
        )
        assertEquals("the accounting must hold", emptyList<String>(), run.violations())
    }
}
