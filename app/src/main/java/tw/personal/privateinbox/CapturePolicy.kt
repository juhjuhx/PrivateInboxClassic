package tw.personal.privateinbox

/** Has no Android dependencies; test with the standalone Kotlin compiler.
 *
 *  The source list itself lives in [SourceRegistry] — this object is the thin
 *  policy layer on top of it, kept for call sites that only need the allow/deny
 *  decision. Adding a platform means editing [SourceRegistry] only. */
object CapturePolicy {
    /** Isolated self-test source. When the app posts its own debug notification via
     *  NotificationManager, the system stamps the StatusBarNotification with our real
     *  applicationId, so this must equal the app's own package. Declared as a distinct,
     *  non-messaging source so the E2E self-test can run without widening the real
     *  messaging allowlist; it is only ever captured when explicitly enabled. */
    const val SELF_TEST = SourceRegistry.SELF_TEST_PACKAGE

    /** Explicit source allowlist, not a global notification recorder. */
    val knownApps: List<Pair<String, String>> =
        SourceRegistry.all.map { it.displayName to it.packageName }

    /** True when the package is a known source AND the user selected it. */
    fun allowed(packageName: String, selected: Set<String>): Boolean =
        SourceRegistry.isKnown(packageName) && packageName in selected

    /**
     * Normalize a notification field for STORAGE.
     *
     * The contract (v0.4.5):
     *
     *   - NO silent truncation. Android may have already truncated the source;
     *     that is reported separately as SOURCE_TRUNCATED. This app never
     *     truncates.
     *   - NO semantic alteration. Whitespace, tabs, newlines, control
     *     characters, zero-width characters, surrogate pairs, combining marks
     *     and emoji are all preserved verbatim. Normalization is a DISPLAY
     *     concern and lives in the UI layer, not here.
     *   - A blank or null input yields null, so the caller can fall back to
     *     the next field or drop the row.
     *
     * Only the null/blank check remains. Everything else is the caller's
     * responsibility, and the caller must treat the result as the ORIGINAL
     * content.
     */
    fun normalize(value: String?): String? = value?.takeIf { it.isNotBlank() }

    fun nameFor(packageName: String): String = SourceRegistry.displayName(packageName)
}
