package tw.personal.privateinbox

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety guard that keeps destructive tests away from real user data.
 *
 * The problem this addresses: the instrumentation tests open the TARGET
 * application's database and clear it. That is correct on an emulator, but on
 * a phone with the real application id it would DELETE THE USER'S MESSAGES.
 *
 * The guard has two halves:
 *
 *  1. A DESTRUCTIVE-OPERATION ASSERTION. Any code that is about to clear
 *     data asks this object first. If the target package is the production
 *     package, the call FAILS FAST with a clear message instead of deleting
 *     anything.
 *
 *  2. A VERIFIABLE RULE. The rule is: destructive operations are allowed only
 *     when the target package is NOT the production package.
 *
 * The production package is `tw.personal.privateinbox`. The destructive tests
 * run against the `devtest` variant, whose application id is
 * `tw.personal.privateinbox.devtest`, which has its own data directory and
 * therefore its own database.
 */
class DestructiveOperationGuardTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theTargetPackageIsWhatTheGuardChecks() {
        println(
            "GUARD target_package=${ctx.packageName} " +
                "production=${DestructiveOperationGuard.PRODUCTION_PACKAGE} " +
                "isolated=${DestructiveOperationGuard.isIsolatedTarget(ctx.packageName)}"
        )
        assertTrue(
            "the guard must classify the target package",
            DestructiveOperationGuard.isIsolatedTarget(ctx.packageName) ||
                ctx.packageName == DestructiveOperationGuard.PRODUCTION_PACKAGE
        )
    }

    @Test
    fun theProductionPackageIsNeverConsideredIsolated() {
        assertFalse(
            "the production package must never be treated as isolated",
            DestructiveOperationGuard.isIsolatedTarget(
                DestructiveOperationGuard.PRODUCTION_PACKAGE
            )
        )
    }

    @Test
    fun theTestVariantIsConsideredIsolated() {
        assertTrue(
            "the test variant must be treated as isolated",
            DestructiveOperationGuard.isIsolatedTarget(
                DestructiveOperationGuard.TEST_PACKAGE
            )
        )
    }

    @Test
    fun aDestructiveOperationOnTheProductionPackageIsRefused() {
        val allowed = DestructiveOperationGuard.mayDestroyData(ctx.packageName)
        if (ctx.packageName == DestructiveOperationGuard.PRODUCTION_PACKAGE) {
            assertFalse(
                "a destructive operation on the production package must be refused",
                allowed
            )
        } else {
            assertTrue(
                "a destructive operation on an isolated package must be allowed",
                allowed
            )
        }
    }

    @Test
    fun theGuardRefusesWithAClearMessage() {
        // The refusal must EXPLAIN itself, so a person running the suite on
        // their phone understands what happened.
        val message = DestructiveOperationGuard.refusalMessage(
            DestructiveOperationGuard.PRODUCTION_PACKAGE
        )
        assertTrue(
            "the refusal must name the production package",
            message.contains(DestructiveOperationGuard.PRODUCTION_PACKAGE)
        )
        assertTrue(
            "the refusal must say the data was NOT deleted",
            message.contains("沒有刪除") || message.contains("not deleted")
        )
    }

    @Test
    fun theIsolationCheckIsIndependentOfTheBuildType() {
        // The guard must not depend on a build type: a RELEASE build installed
        // on a phone must still be protected.
        assertEquals(
            "the production package must be recognised regardless of the build",
            false,
            DestructiveOperationGuard.isIsolatedTarget(
                DestructiveOperationGuard.PRODUCTION_PACKAGE
            )
        )
    }

    @Test
    fun aDestructiveOperationOnTheProductionPackageThrows() {
        // The fail-fast path: it must THROW, not silently do nothing.
        if (ctx.packageName == DestructiveOperationGuard.PRODUCTION_PACKAGE) {
            var threw = false
            try {
                DestructiveOperationGuard.requireIsolation(ctx.packageName)
            } catch (e: IllegalStateException) {
                threw = true
                assertTrue(
                    "the refusal must say the data was not deleted",
                    e.message?.contains("沒有刪除") == true
                )
            }
            assertTrue("requireIsolation must throw for the production package", threw)
        } else {
            // On an isolated package it must NOT throw.
            DestructiveOperationGuard.requireIsolation(ctx.packageName)
        }
    }

    @Test
    fun theVariantNamesResolveToTheRunningPackage() {
        assertEquals(
            "the listener component must use the running application id",
            "${ctx.packageName}/tw.personal.privateinbox.PrivateNotificationListener",
            VariantNames.listenerComponent(ctx.packageName)
        )
        assertEquals(
            "the instrumentation package must be derived from the target",
            "${ctx.packageName}.test",
            VariantNames.instrumentationPackage(ctx.packageName)
        )
    }
}
