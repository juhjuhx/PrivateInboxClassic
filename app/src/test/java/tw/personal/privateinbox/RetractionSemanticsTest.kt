package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Retraction semantics (v0.4.5).
 *
 * The contract: a notification removal is OBSERVED for diagnostics only.
 * Committed rows stay immutable — the archive exists precisely so a message
 * retracted in the source app remains readable here. These tests pin the
 * mapping from platform removal reasons to stable reason classes; the
 * immutability itself is covered by the instrumented capture tests, because
 * only a real NotificationListenerService can deliver a removal.
 */
class RetractionSemanticsTest {

    @Test
    fun `source-app cancellations are classified as source retraction`() {
        assertEquals(
            CaptureDiagnostics.REMOVAL_SOURCE_CANCELLED,
            removalReasonClassOf(android.service.notification.NotificationListenerService.REASON_APP_CANCEL)
        )
        assertEquals(
            CaptureDiagnostics.REMOVAL_SOURCE_CANCELLED,
            removalReasonClassOf(android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL)
        )
        assertEquals(
            CaptureDiagnostics.REMOVAL_SOURCE_CANCELLED,
            removalReasonClassOf(android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED)
        )
    }

    @Test
    fun `user dismissals are classified as user action`() {
        assertEquals(
            CaptureDiagnostics.REMOVAL_USER_DISMISSED,
            removalReasonClassOf(android.service.notification.NotificationListenerService.REASON_CANCEL)
        )
        assertEquals(
            CaptureDiagnostics.REMOVAL_USER_DISMISSED,
            removalReasonClassOf(android.service.notification.NotificationListenerService.REASON_CANCEL_ALL)
        )
        assertEquals(
            CaptureDiagnostics.REMOVAL_USER_DISMISSED,
            removalReasonClassOf(android.service.notification.NotificationListenerService.REASON_USER_STOPPED)
        )
    }

    @Test
    fun `unspecified and unknown reasons are never guessed`() {
        // The 2-arg callback gives no reason.
        assertEquals(
            CaptureDiagnostics.REMOVAL_REASON_UNKNOWN,
            removalReasonClassOf(-1)
        )
        // Reasons this app does not interpret.
        assertEquals(
            CaptureDiagnostics.REMOVAL_REASON_UNKNOWN,
            removalReasonClassOf(android.service.notification.NotificationListenerService.REASON_CLICK)
        )
        assertEquals(
            CaptureDiagnostics.REMOVAL_REASON_UNKNOWN,
            removalReasonClassOf(android.service.notification.NotificationListenerService.REASON_TIMEOUT)
        )
        assertEquals(
            CaptureDiagnostics.REMOVAL_REASON_UNKNOWN,
            removalReasonClassOf(999_999)
        )
    }

    @Test
    fun `reason classes are distinct`() {
        val classes = setOf(
            CaptureDiagnostics.REMOVAL_SOURCE_CANCELLED,
            CaptureDiagnostics.REMOVAL_USER_DISMISSED,
            CaptureDiagnostics.REMOVAL_REASON_UNKNOWN
        )
        assertEquals(3, classes.size)
    }
}
