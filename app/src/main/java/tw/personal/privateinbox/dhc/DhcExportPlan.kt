package tw.personal.privateinbox.dhc

import android.content.Context
import android.net.Uri
import tw.personal.privateinbox.InboxDatabase
import tw.personal.privateinbox.SourceRegistry

/**
 * The export PLAN: what would be exported, before anything is written.
 *
 * The plan is built first and shown to the user, so the export is always an
 * explicit, informed action. Nothing is written to a file until the user has
 * seen the plan and chosen a destination.
 */
object DhcExportPlan {

    /** The kinds of export the user can choose. */
    enum class Scope {
        /** Every message, from every source. */
        ALL,

        /** Only the selected sources. */
        SELECTED_SOURCES,

        /** Only the messages in a date range. */
        DATE_RANGE
    }

    /**
     * A filter for the export.
     *
     * The filters are ANDed: a message is exported when it matches all of
     * the ones that are set.
     */
    data class Filter(
        val scope: Scope = Scope.ALL,
        /** The package names to export, for [Scope.SELECTED_SOURCES]. */
        val packages: Set<String> = emptySet(),
        /** The inclusive start, in epoch milliseconds, for [Scope.DATE_RANGE]. */
        val fromTimestamp: Long? = null,
        /** The exclusive end, in epoch milliseconds, for [Scope.DATE_RANGE]. */
        val toTimestamp: Long? = null,
        /** Only messages at or after this cursor, for an incremental export. */
        val sinceTimestamp: Long? = null,
        /** The id of the last exported row, for an incremental export. */
        val sinceId: Long? = null,
        /** Only private messages, excluding social activity. */
        val privateOnly: Boolean = true
    ) {
        /** True when this filter describes an incremental export. */
        val isIncremental: Boolean get() = sinceTimestamp != null || sinceId != null
    }

    /** What the plan contains, and what it would cost. */
    data class Plan(
        val messages: List<DhcExporter.ExportedMessage>,
        val totalRows: Int,
        val filteredOut: Int,
        val fromTimestamp: Long?,
        val toTimestamp: Long?,
        val sources: List<String>,
        val isIncremental: Boolean
    ) {
        /** A short, content-free summary for the confirmation dialog. */
        fun summary(): String = buildString {
            append("將匯出 $totalRows 則訊息")
            if (filteredOut > 0) append("（另有 $filteredOut 則不符合篩選條件）")
            append("。\n")
            append("來源：${sources.joinToString("、").ifEmpty { "（無）" }}")
            append("\n")
            if (isIncremental) append("這是增量匯出，只包含上次之後的新訊息。\n")
            append("\n匯出檔會以你提供的密碼加密（AES-256-GCM）。")
            append("密碼不會被儲存，也不會出現在任何日誌或診斷中。")
        }
    }

    /**
     * Build the plan.
     *
     * The rows are read from the database and turned into the exported form.
     * The message CONTENT is not included in the plan's summary: only the
     * counts and the source names.
     */
    fun build(context: Context, filter: Filter, db: InboxDatabase): Plan {
        val rows = db.readAllForExport()
        var total = 0
        var filteredOut = 0
        val messages = ArrayList<DhcExporter.ExportedMessage>()
        val sources = linkedSetOf<String>()

        for (row in rows) {
            total++
            if (!matches(row, filter)) {
                filteredOut++
                continue
            }
            messages += row
            sources += row.sourceApplication
        }
        return Plan(
            messages = messages,
            totalRows = total,
            filteredOut = filteredOut,
            fromTimestamp = filter.fromTimestamp,
            toTimestamp = filter.toTimestamp,
            sources = sources.toList(),
            isIncremental = filter.isIncremental
        )
    }

    private fun matches(row: DhcExporter.ExportedMessage, filter: Filter): Boolean {
        if (filter.scope == Scope.SELECTED_SOURCES && filter.packages.isNotEmpty()) {
            if (row.sourceApplication !in filter.packages) return false
        }
        if (filter.fromTimestamp != null && row.receivedAt < filter.fromTimestamp) return false
        if (filter.toTimestamp != null && row.receivedAt >= filter.toTimestamp) return false
        if (filter.sinceTimestamp != null && row.receivedAt < filter.sinceTimestamp) return false
        if (filter.privateOnly && !isPrivate(row.messageKind)) return false
        return true
    }

    /** True for the message kinds that are a private message. */
    private fun isPrivate(kind: String): Boolean = when (kind) {
        SourceRegistry.MessageKind.DIRECT_MESSAGE.name,
        SourceRegistry.MessageKind.GROUP_MESSAGE.name,
        SourceRegistry.MessageKind.MENTION.name,
        SourceRegistry.MessageKind.REPLY.name -> true
        else -> false
    }
}
