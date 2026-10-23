package tw.personal.privateinbox

import android.content.Context
import android.content.pm.PackageManager

/**
 * The variant-specific names the tests need.
 *
 * The production variant and the isolated devtest variant differ in three
 * places: the application id, the debug provider's authority, and the
 * signature-level permission's name. A test that hardcodes any of them only
 * works against one variant.
 *
 * These helpers resolve the names from the RUNNING package, so the same test
 * source works against both.
 */
object VariantNames {

    /**
     * The debug settings permission for the given package.
     *
     * The devtest variant declares `...DEBUG_SETTINGS.devtest`, the prod
     * variant `...DEBUG_SETTINGS`.
     */
    fun debugSettingsPermission(packageName: String): String {
        val suffix = if (packageName.endsWith(".devtest")) ".devtest" else ""
        return "tw.personal.privateinbox.permission.DEBUG_SETTINGS$suffix"
    }

    /**
     * The instrumentation APK's package name for the given target package.
     *
     * The instrumentation APK is named after the target, with `.test`
     * appended: `tw.personal.privateinbox.test` for the prod variant and
     * `tw.personal.privateinbox.devtest.test` for the devtest one.
     */
    fun instrumentationPackage(targetPackage: String): String = "$targetPackage.test"

    /**
     * The debug settings permission, resolved from a context.
     */
    fun debugSettingsPermission(context: Context): String =
        debugSettingsPermission(context.packageName)

    /**
     * The instrumentation package name, resolved from a context.
     */
    fun instrumentationPackage(context: Context): String =
        instrumentationPackage(context.packageName)

    /**
     * The debug provider's authority for the given package.
     *
     * The manifest declares it as `tw.personal.privateinbox.debugsettings`
     * with the variant suffix appended, so the devtest variant is
     * `tw.personal.privateinbox.debugsettings.devtest`. The code must produce
     * the same string, or the provider is "unknown".
     */
    fun debugSettingsAuthority(packageName: String): String {
        val suffix = if (packageName.endsWith(".devtest")) ".devtest" else ""
        return "tw.personal.privateinbox.debugsettings$suffix"
    }

    /**
     * The notification listener component for the given application id.
     *
     * The service is declared in the NAMESPACE package, which does not change
     * with the application id: the devtest variant's service is still
     * `tw.personal.privateinbox.PrivateNotificationListener`, even though its
     * application id is `tw.personal.privateinbox.devtest`.
     *
     * The `cmd notification allow_listener` form is
     * `<applicationId>/<fully qualified service>`, so the two halves come
     * from different places.
     */
    fun listenerComponent(applicationId: String): String =
        "$applicationId/tw.personal.privateinbox.PrivateNotificationListener"

    /**
     * The permissions the target application actually declares.
     *
     * Used by the access-control test to check the real declaration rather
     * than a hardcoded string.
     */
    fun declaredPermissions(context: Context): List<String> = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        )
        info.requestedPermissions?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * The permissions the application DECLARES (its own custom permissions).
     */
    fun ownPermissions(context: Context): List<String> = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        )
        info.permissions?.map { it.name } ?: emptyList()
    }.getOrDefault(emptyList())
}
