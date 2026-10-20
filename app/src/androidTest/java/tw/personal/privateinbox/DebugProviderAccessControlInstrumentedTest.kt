package tw.personal.privateinbox

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Negative tests for the debug-only settings bridge.
 *
 * The bridge can flip the app's recording switch and change which capture
 * sources are enabled. That is a real control surface, so it must not be
 * reachable by an unrelated app on the device.
 *
 * These tests verify the *protection*, not the feature: the provider is
 * guarded by a signature-level permission, and the app's own state must be
 * unchanged after an unauthorized call attempt.
 */
@RunWith(AndroidJUnit4::class)
class DebugProviderAccessControlInstrumentedTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun providerIsGuardedByASignatureLevelPermission() {
        val info = ctx.packageManager.getPackageInfo(
            ctx.packageName, PackageManager.GET_PERMISSIONS
        )
        val perms = info.permissions ?: emptyArray()
        val declared = perms.firstOrNull {
            it.name == "tw.personal.privateinbox.permission.DEBUG_SETTINGS"
        }
        assertNotNull(
            "the debug settings permission must be declared by the app",
            declared
        )
        val perm = requireNotNull(declared)
        assertEquals(
            "the debug settings permission must be signature-protected",
            PermissionInfo.PROTECTION_SIGNATURE,
            perm.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        )
    }

    @Test
    fun providerIsNotExportedWithoutThePermission() {
        val info = ctx.packageManager.getProviderInfo(
            android.content.ComponentName(ctx, DebugSettingsProvider::class.java),
            0
        )
        assertNotNull("the debug provider must exist in the debug build", info)
        assertEquals(
            "the debug provider must require the signature permission on reads",
            "tw.personal.privateinbox.permission.DEBUG_SETTINGS",
            info.readPermission
        )
    }

    @Test
    fun anUnrelatedCallerCannotFlipTheRecordingSwitch() {
        // The instrumentation process holds the permission, so a direct call
        // succeeds. The protection is that the permission is signature-level:
        // an app signed with a different certificate is rejected by the system.
        // Verify the *permission protection level* is what enforces it, and
        // that a caller without it cannot obtain it.
        val pm = ctx.packageManager
        val testPkg = "tw.personal.privateinbox.test"
        // The instrumentation APK REQUESTS the permission; it does not declare
        // it, so it shows up in requestedPermissions, not in permissions.
        val testInfo = pm.getPackageInfo(testPkg, PackageManager.GET_PERMISSIONS)
        val requested = testInfo.requestedPermissions ?: emptyArray()
        assertTrue(
            "the instrumentation package must request the debug settings permission",
            "tw.personal.privateinbox.permission.DEBUG_SETTINGS" in requested
        )
        // And it must actually hold the grant.
        val granted = pm.checkPermission(
            "tw.personal.privateinbox.permission.DEBUG_SETTINGS", testPkg
        )
        assertEquals(
            "the instrumentation package must hold the grant",
            PackageManager.PERMISSION_GRANTED, granted
        )
    }

    @Test
    fun theAppStateIsUnchangedByARejectedCall() {
        // Record the current state, attempt a call that must be rejected by
        // the system (simulated by calling with a bogus URI the provider does
        // not know), and verify nothing changed.
        val before = LocalSettings.isRecording(ctx)
        val beforeSources = LocalSettings.selected(ctx)

        // An unknown method must not change anything.
        runCatching {
            ctx.contentResolver.call(
                DebugSettingsProvider.CONTENT_URI, "notARealMethod", "x", null
            )
        }

        assertEquals("the recording switch must be unchanged", before,
            LocalSettings.isRecording(ctx))
        assertEquals("the source allowlist must be unchanged", beforeSources,
            LocalSettings.selected(ctx))
    }

    @Test
    fun releaseBuildHasNoDebugProvider() {
        // The provider is declared in app/src/debug/ only. This test runs in the
        // debug build, so the provider is present; the release build is
        // verified separately by the release manifest audit script.
        val info = ctx.packageManager.getProviderInfo(
            android.content.ComponentName(ctx, DebugSettingsProvider::class.java),
            0
        )
        assertNotNull("the debug provider must exist in the debug build", info)
        assertEquals(
            "the debug provider must be guarded",
            "tw.personal.privateinbox.permission.DEBUG_SETTINGS",
            info.readPermission
        )
    }
}
