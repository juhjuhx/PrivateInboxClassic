package tw.personal.privateinbox.dhc

import android.content.Context
import android.net.Uri
import tw.personal.privateinbox.InboxDatabase
import java.io.IOException

/**
 * The export flow.
 *
 * The order of operations matters and is deliberate:
 *
 *  1. The user chooses WHAT to export (a plan).
 *  2. The user chooses WHERE to put it (the Storage Access Framework).
 *  3. The user provides a PASSPHRASE.
 *  4. The bundle is written, encrypted, to the chosen destination.
 *
 * The passphrase is used to derive the key and is then discarded. It is never
 * stored, never logged, and never included in the export.
 */
object DhcExportService {

    /** The result of an export. */
    sealed class Result {
        /** The bundle was written. */
        data class Written(
            val messages: Int,
            val bytes: Long,
            val exportId: String
        ) : Result()

        /** The export was cancelled. */
        object Cancelled : Result()

        /** The export failed. The type name only, never any content. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Write the bundle to the destination.
     *
     * @param context the app context
     * @param destination the SAF uri the user chose
     * @param plan the messages to export
     * @param passphrase the user's passphrase; it is zeroed after use
     */
    fun export(
        context: Context,
        destination: Uri,
        plan: DhcExportPlan.Plan,
        passphrase: CharArray
    ): Result {
        return try {
            val exportId = DhcExporter.newExportId()
            val deviceId = DhcExporter.deviceId(context)
            val createdAt = System.currentTimeMillis()

            val json = DhcExporter.buildBundleJson(
                exportId = exportId,
                sourceDeviceId = deviceId,
                exportCreatedAt = createdAt,
                messages = plan.messages,
                cursor = plan.cursor()
            )
            val container = DhcExporter.encryptBundle(json, passphrase)
            // The passphrase is not needed any more. It is zeroed so it does
            // not linger in memory.
            passphrase.fill('\u0000')

            context.contentResolver.openOutputStream(destination)?.use { stream ->
                stream.write(container)
                stream.flush()
            } ?: return Result.Failed("openOutputStream returned null")

            Result.Written(plan.messages.size, container.size.toLong(), exportId)
        } catch (e: IOException) {
            Result.Failed(e.javaClass.simpleName)
        } catch (e: Exception) {
            Result.Failed(e.javaClass.simpleName)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /**
     * The cursor for an incremental export.
     *
     * It is the (received_at, id) pair of the newest exported message, so the
     * next export can ask for everything after it. It is stored in the
     * bundle in the clear: it contains no content, only a timestamp and a
     * row id.
     */
    private fun DhcExportPlan.Plan.cursor(): String? {
        val newest = messages.maxByOrNull { it.receivedAt } ?: return null
        return "${newest.receivedAt}:${newest.messageId}"
    }
}
