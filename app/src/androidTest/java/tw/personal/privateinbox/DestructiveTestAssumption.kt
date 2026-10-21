package tw.personal.privateinbox

import android.content.Context
import org.junit.Assume.assumeTrue

/**
 * The test-side entry point for the destructive tests.
 *
 * The destructive tests clear the database, so they are only meaningful in an
 * ISOLATED package. When the suite runs against the production package — which
 * is the application the user installs — they are SKIPPED with a reason, not
 * silently ignored and not failed.
 *
 * The distinction matters:
 *
 *  - SKIPPED means "this test did not run here, and here is why".
 *  - FAILED would mean the product is broken, which it is not.
 *
 * The protection is still proven on the production package by
 * [DestructiveOperationGuardTest], which asserts that the guard REFUSES.
 */
object DestructiveTestAssumption {

    /**
     * Skip the current test when the target package is not isolated.
     *
     * @return true when the test may proceed
     */
    fun requireIsolated(context: Context): Boolean {
        val isolated = DestructiveOperationGuard.isIsolatedTarget(context.packageName)
        assumeTrue(
            "SKIPPED: this test destroys the database, so it only runs against " +
                "the isolated variant (${DestructiveOperationGuard.TEST_PACKAGE}). " +
                "The target here is ${context.packageName}, and nothing was deleted.",
            isolated
        )
        return true
    }
}
