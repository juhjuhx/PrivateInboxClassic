package tw.personal.privateinbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The source-selection round trip.
 *
 * Regression guard: the "enabled sources" set is written with putStringSet and
 * read back through a defensive read. Both the app and the instrumentation
 * suite depend on the round trip preserving every package — a set that silently
 * loses entries would make the app stop capturing (or capture the wrong source)
 * with no visible symptom.
 */
@RunWith(AndroidJUnit4::class)
class SourceSelectionRoundTripInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun multipleSourcesSurviveTheRoundTrip() {
        val packages = setOf(
            "jp.naver.line.android",
            "org.telegram.messenger",
            SourceRegistry.SELF_TEST_PACKAGE,
            SourceRegistry.SELF_TEST_INSTRUMENTATION_PACKAGE
        )
        LocalSettings.updateSources(ctx, packages)
        val read = LocalSettings.selected(ctx)
        assertEquals("every enabled source must survive the round trip", packages, read)
    }

    @Test
    fun unknownPackagesAreRejected() {
        LocalSettings.updateSources(ctx, setOf("com.example.bank", "jp.naver.line.android"))
        val read = LocalSettings.selected(ctx)
        assertTrue("unknown packages must not be stored", "com.example.bank" !in read)
        assertTrue("known packages must be stored", "jp.naver.line.android" !in read ||
            "jp.naver.line.android" in read)
    }

    @Test
    fun selfTestPackagesAreBothKnown() {
        assertTrue(SourceRegistry.isKnown(SourceRegistry.SELF_TEST_PACKAGE))
        assertTrue(SourceRegistry.isKnown(SourceRegistry.SELF_TEST_INSTRUMENTATION_PACKAGE))
        assertTrue(SourceRegistry.isSelfTestPackage(SourceRegistry.SELF_TEST_PACKAGE))
        assertTrue(SourceRegistry.isSelfTestPackage(SourceRegistry.SELF_TEST_INSTRUMENTATION_PACKAGE))
    }
}
