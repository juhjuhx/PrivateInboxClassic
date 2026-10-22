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
     * Collapse the whitespace runs in a piece of text.
     *
     * The pattern is COMPILED ONCE, at class-load time.
     *
     * It used to be `Regex("[\\t\\r ]+")` written inline, which compiled a
     * fresh Regex object on EVERY call. The notification callback calls this
     * twice per message -- once for the body, once for the sender -- and a
     * group notification carries up to 25 messages, so a single notification
     * compiled up to 50 Regex objects ON THE MAIN THREAD.
     *
     * A compiled Regex is immutable and thread-safe, so one shared instance
     * is correct.
     */
    private val WHITESPACE_RUNS = Regex("[\\t\\r ]+")

    /** The maximum length of a stored message body. */
    private const val MAX_BODY_LENGTH = 20_000

    fun normalize(value: String?): String? = value
        ?.trim()
        ?.replace(WHITESPACE_RUNS, " ")
        ?.take(MAX_BODY_LENGTH)
        ?.takeIf { it.isNotBlank() }

    fun nameFor(packageName: String): String = SourceRegistry.displayName(packageName)
}
