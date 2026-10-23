package tw.personal.privateinbox

import android.content.Context
import android.util.Log

/**
 * The guard that keeps destructive test operations away from real user data.
 *
 * **The problem.** The instrumentation tests open the TARGET application's
 * database and clear it. That is correct on an emulator, but the target
 * package is `tw.personal.privateinbox` — the SAME package the user installs.
 * Running the suite on a phone that has the real app would DELETE THE USER'S
 * SAVED MESSAGES.
 *
 * **The rule.** A destructive operation is allowed only when the target
 * package is an ISOLATED test package. The production package is never
 * isolated, no matter which build type is installed.
 *
 * **Why code and not a reminder.** A reminder in the documentation is not a
 * control. Someone will eventually run the suite on their phone, or a script
 * will be pointed at a real device by accident. The refusal has to live in the
 * code path that performs the deletion.
 *
 * **The isolation mechanism.** The destructive tests run against the
 * `devtest` variant, whose application id is `tw.personal.privateinbox.devtest`.
 * Android gives each application id its own data directory, so the test
 * variant has its own `inbox.db` and cannot touch the real one. The
 * permission names are suffixed per variant so the two cannot impersonate
 * each other.
 */
object DestructiveOperationGuard {

    private const val TAG = "PrivateInboxGuard"

    /** The application id the user installs. NEVER isolated. */
    const val PRODUCTION_PACKAGE = "tw.personal.privateinbox"

    /** The application id the destructive tests run against. */
    const val TEST_PACKAGE = "tw.personal.privateinbox.devtest"

    /**
     * The application ids that are safe to destroy data in.
     *
     * The `devtest` variant and any id under the `.test` namespace. The
     * production id is deliberately NOT in this set.
     */
    private val isolatedPackages = setOf(
        TEST_PACKAGE,
        "tw.personal.privateinbox.test",
        "tw.personal.privateinbox.devtest.test"
    )

    /**
     * True when the given package id is safe to run destructive tests against.
     *
     * The production package is never isolated. That is the whole point: the
     * check is by package id, not by build type, so a release build on a
     * phone is protected too.
     */
    fun isIsolatedTarget(packageName: String): Boolean =
        packageName in isolatedPackages

    /**
     * True when data may be destroyed in the given package.
     *
     * This is the single decision point. Every destructive call site asks it.
     */
    fun mayDestroyData(packageName: String): Boolean = isIsolatedTarget(packageName)

    /**
     * The message shown when a destructive operation is refused.
     *
     * It must make two things unambiguous: that the data was NOT deleted, and
     * what to do instead.
     */
    fun refusalMessage(packageName: String): String =
        "拒絕執行破壞性操作：目標套件 $packageName 是正式套件。\n" +
            "資料沒有刪除。\n" +
            "破壞性測試只能在隔離的測試套件上執行" +
            "（${isolatedPackages.joinToString("、")}）。\n" +
            "在正式套件上請使用唯讀的診斷流程。"

    /**
     * Assert that data may be destroyed, or fail fast.
     *
     * @throws IllegalStateException when the target is not isolated
     */
    fun requireIsolation(packageName: String) {
        if (!mayDestroyData(packageName)) {
            val message = refusalMessage(packageName)
            Log.e(TAG, message)
            throw IllegalStateException(message)
        }
    }

    /**
     * The guard for a context.
     *
     * @throws IllegalStateException when the context's package is not isolated
     */
    fun requireIsolation(context: Context) {
        requireIsolation(context.packageName)
    }
}
