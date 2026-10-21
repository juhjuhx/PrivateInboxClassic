package tw.personal.privateinbox

import android.content.Context

/**
 * The ONLY sanctioned way for a test to destroy the app's database.
 *
 * **Why this exists.** The instrumentation tests used to call
 * `InboxDatabase.clear()` directly. `clear()` is also the app's own
 * user-facing "erase everything" action, so it cannot simply be made to fail
 * — that would break a real feature.
 *
 * The destructive tests instead go through this object, which REFUSES to run
 * when the target package is the production one. That turns the safety
 * property from a documentation note into a code path that cannot be skipped
 * by accident.
 *
 * The isolation itself comes from the build: the destructive tests run against
 * the `devtest` variant, whose application id is
 * `tw.personal.privateinbox.devtest`. Android gives each application id its
 * own data directory, so that variant has its own `inbox.db` and physically
 * cannot reach the user's.
 *
 * The check is by PACKAGE ID, not by build type, so a release build installed
 * on a phone is protected as well.
 */
object TestDataIsolation {

    /**
     * Erase the database, but only in an isolated package.
     *
     * @throws IllegalStateException when the target is not isolated. Nothing
     *         is deleted in that case.
     */
    fun clearDatabase(context: Context) {
        DestructiveOperationGuard.requireIsolation(context)
        val db = InboxDatabase(context)
        try {
            db.clear()
        } finally {
            db.close()
        }
    }

    /**
     * Purge the rows that cannot be decrypted, but only in an isolated package.
     *
     * This is the destructive operation the keystore-recovery tests need, and
     * it deletes real rows, so it is guarded exactly like [clearDatabase].
     *
     * @throws IllegalStateException when the target is not isolated. Nothing
     *         is deleted in that case.
     */
    fun purgeUndecryptableRows(context: Context): Int {
        DestructiveOperationGuard.requireIsolation(context)
        val db = InboxDatabase(context)
        try {
            return db.purgeUndecryptableRows()
        } finally {
            db.close()
        }
    }

    /**
     * Erase the database, returning false instead of throwing when the target
     * is not isolated.
     *
     * For a caller that wants to SKIP rather than FAIL. The refusal is still
     * logged, so a skipped test is visible rather than silent.
     *
     * @return true when the data was erased, false when the operation was
     *         refused
     */
    fun tryClearDatabase(context: Context): Boolean {
        if (!DestructiveOperationGuard.mayDestroyData(context.packageName)) {
            android.util.Log.w(
                "PrivateInboxGuard",
                DestructiveOperationGuard.refusalMessage(context.packageName)
            )
            return false
        }
        clearDatabase(context)
        return true
    }
}
