package tw.personal.privateinbox

/**
 * Stable identity of a captured conversation.
 *
 * The Android notification API exposes a conversation *title* (the display name the
 * source app chose) and, on newer platforms, a conversation *shortcut id* which the
 * source app uses to identify the actual conversation. The shortcut id is the
 * stronger signal: two different contacts can share a display name, but they do not
 * share a shortcut id.
 *
 * When no shortcut id is available we fall back to the display title, which is the
 * best the platform offers. The package name is always part of the key, so the same
 * contact in LINE and in Messenger never merges.
 */
data class ConversationIdentity(
    /** The source application package name. Always part of the key. */
    val packageName: String,
    /** Platform-provided conversation id, when the source app supplied one. */
    val shortcutId: String? = null,
    /** Display title as shown by the source app. */
    val title: String = "",
    /** True when the conversation is a group room rather than a single contact. */
    val isGroup: Boolean = false
) {
    /**
     * The grouping key used for the inbox list.
     *
     * Format: `package \0 shortcutId \0 title`.
     * - With a shortcut id the key is stable even if the contact renames themselves.
     * - Without one, the title is used (the platform's only signal).
     * - The package prefix keeps different apps completely separate.
     */
    val key: String
        get() = buildString {
            append(packageName)
            append('\u0000')
            append(shortcutId?.takeIf { it.isNotBlank() } ?: title)
            append('\u0000')
            append(title)
        }

    /** True when this identity carries no real discriminator (package + empty title
     *  and no shortcut id) — such rows cannot be grouped meaningfully. */
    val isEmpty: Boolean
        get() = shortcutId.isNullOrBlank() && title.isBlank()

    companion object {
        /** Build an identity from the values a notification provides. */
        fun of(
            packageName: String,
            title: String?,
            shortcutId: String? = null,
            isGroup: Boolean = false
        ): ConversationIdentity = ConversationIdentity(
            packageName = packageName,
            shortcutId = shortcutId?.takeIf { it.isNotBlank() },
            title = title.orEmpty(),
            isGroup = isGroup
        )
    }
}
