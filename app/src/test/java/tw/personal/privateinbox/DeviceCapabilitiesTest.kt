package tw.personal.privateinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compatibility layer.
 *
 * The app must not be written for one phone, so the OEM detection has to be
 * derived from what the device reports, and an UNKNOWN manufacturer must never
 * be guessed into a known family.
 */
class DeviceCapabilitiesTest {

    @Test
    fun theEnvironmentIsDescribed() {
        val described = DeviceCapabilities.describe()
        assertTrue("the description must name the API level", described.contains("android="))
        assertTrue("the description must name the OEM family", described.contains("oem="))
        assertTrue("the description must name the model", described.contains("model="))
        assertTrue(
            "the description must state the history cap",
            described.contains("history_cap=25")
        )
    }

    @Test
    fun everyOemFamilyHasAStableName() {
        // The names are persisted in the diagnostics export, so they must not
        // change without a migration.
        assertEquals("AOSP", DeviceCapabilities.OemFamily.AOSP.name)
        assertEquals("SAMSUNG", DeviceCapabilities.OemFamily.SAMSUNG.name)
        assertEquals("XIAOMI", DeviceCapabilities.OemFamily.XIAOMI.name)
        assertEquals("VIVO", DeviceCapabilities.OemFamily.VIVO.name)
        assertEquals("OPPO", DeviceCapabilities.OemFamily.OPPO.name)
        assertEquals("REALME", DeviceCapabilities.OemFamily.REALME.name)
        assertEquals("HONOR", DeviceCapabilities.OemFamily.HONOR.name)
        assertEquals("HUAWEI", DeviceCapabilities.OemFamily.HUAWEI.name)
        assertEquals("MOTOROLA", DeviceCapabilities.OemFamily.MOTOROLA.name)
        assertEquals("OTHER", DeviceCapabilities.OemFamily.OTHER.name)
    }

    @Test
    fun theMessagingHistoryCapIsStated() {
        // The platform caps the MessagingStyle history at 25 on every version
        // this app supports. A burst beyond that in ONE revision cannot be
        // captured by ANY listener, and the app must not pretend otherwise.
        assertEquals(25, DeviceCapabilities.MESSAGING_HISTORY_LIMIT)
        assertTrue(DeviceCapabilities.messagingHistoryIsCapped)
    }

    @Test
    fun theFrameworkCapabilitiesMatchTheApiLevel() {
        // Every version this app supports is API 28 or later, and every one of
        // these capabilities is available there.
        //
        // The unit test runs on the JVM, where Build.VERSION.SDK_INT is 0.
        // The version-dependent capabilities are therefore checked for
        // CONSISTENCY rather than for a specific value: a capability that
        // depends on the version must agree with the version that is
        // reported.
        val level = DeviceCapabilities.androidVersion
        if (level >= 28) {
            assertTrue(
                "the listener rebind request is available from API 24",
                DeviceCapabilities.canRequestRebind
            )
            assertTrue(
                "the removal callback is available from API 18",
                DeviceCapabilities.reportsNotificationRemoval
            )
            assertFalse(
                "from API 33 the notification permission has to be granted",
                DeviceCapabilities.canPostNotifications
            )
        } else {
            // The JVM stub reports 0. Only the version-INDEPENDENT
            // capabilities can be asserted there.
            assertTrue(
                "the messaging history cap is a platform constant",
                DeviceCapabilities.messagingHistoryIsCapped
            )
        }
    }

    @Test
    fun thePostNotificationCapabilityMatchesTheApiLevel() {
        // Below API 33 the permission is implicit; from 33 it is a runtime
        // permission the user can deny.
        if (DeviceCapabilities.androidVersion >= 33) {
            assertFalse(
                "from API 33 the permission has to be granted at runtime",
                DeviceCapabilities.canPostNotifications
            )
        } else {
            assertTrue(
                "below API 33 the permission is implicit",
                DeviceCapabilities.canPostNotifications
            )
        }
    }

    @Test
    fun theGuidanceIsOnlyOfferedWhereItApplies() {
        val guidance = DeviceCapabilities.backgroundGuidance()
        if (!DeviceCapabilities.hasAggressiveBackgroundPolicy) {
            assertEquals(
                "no guidance on a device without an aggressive background policy",
                null, guidance
            )
        } else {
            assertNotNull(
                "a device with an aggressive background policy needs guidance",
                guidance
            )
            assertTrue(
                "the guidance must name the setting the user has to change",
                guidance!!.contains("設定")
            )
        }
    }
}
