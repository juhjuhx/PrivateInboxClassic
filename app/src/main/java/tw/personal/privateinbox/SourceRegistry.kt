package tw.personal.privateinbox

/**
 * Single source of truth for which messaging platforms this app can capture and how
 * their notifications are classified.
 *
 * Adding a new platform means adding one [Source] here — the UI filters, the source
 * settings screen and the classifier all read from this registry. Nothing else needs
 * to change.
 *
 * Classification is based on the real Android package name, never on notification
 * text or title guessing.
 */
object SourceRegistry {

    /** How a captured notification should be treated by the inbox. */
    enum class MessageKind {
        /** A one-to-one private message. */
        DIRECT_MESSAGE,

        /** A message inside a group conversation. */
        GROUP_MESSAGE,

        /** A direct message that explicitly mentions the account owner. */
        MENTION,

        /** A reply that quotes or continues a previous message. */
        REPLY,

        /** A social notification (like, follow, recommendation). Not a private chat. */
        SOCIAL_ACTIVITY,

        /** Could not be classified with confidence. */
        UNKNOWN;

        /** Only message-like kinds belong in the default private-message inbox. */
        val isMessageLike: Boolean
            get() = this == DIRECT_MESSAGE || this == GROUP_MESSAGE ||
                    this == MENTION || this == REPLY
    }

    /** A capturable messaging platform. */
    data class Source(
        val packageName: String,
        val displayName: String,
        /** Short label used in the filter bar, e.g. "IG". */
        val shortLabel: String,
        /** Ordering in the filter bar. */
        val order: Int,
        /** True when the platform is a social network rather than a pure messenger. */
        val isSocial: Boolean,
        /** True for the built-in self-test source (never a real platform). */
        val isSelfTest: Boolean = false
    )

    /** The isolated self-test source. The system stamps our own package name onto
     *  notifications this app posts, so it must equal the application id. */
    const val SELF_TEST_PACKAGE = "tw.personal.privateinbox"

    /**
     * The instrumentation APK is a separate package with its own UID, so a
     * notification posted from the test process is stamped with THIS package
     * rather than the app's. It is the same self-test channel and is treated
     * identically.
     */
    const val SELF_TEST_INSTRUMENTATION_PACKAGE = "tw.personal.privateinbox.test"

    /** True when the package is one of the self-test channels. */
    fun isSelfTestPackage(packageName: String): Boolean =
        packageName == SELF_TEST_PACKAGE || packageName == SELF_TEST_INSTRUMENTATION_PACKAGE

    private val sources = listOf(
        Source("jp.naver.line.android", "LINE", "LINE", 1, isSocial = false),
        Source("com.instagram.android", "Instagram", "IG", 2, isSocial = true),
        Source("com.facebook.orca", "Messenger", "MS", 3, isSocial = false),
        Source("com.instagram.barcelona", "Threads", "TH", 4, isSocial = true),
        Source("com.whatsapp", "WhatsApp", "WA", 5, isSocial = false),
        Source("org.telegram.messenger", "Telegram", "TG", 6, isSocial = false),
        Source("org.thunderdog.challegram", "Telegram X", "TGX", 7, isSocial = false),
        Source("com.microsoft.teams", "Teams", "MSFT", 8, isSocial = false),
        Source("com.kakao.talk", "KakaoTalk", "KAKAO", 9, isSocial = false),
        Source(SELF_TEST_PACKAGE, "Private Inbox Self Test", "TEST", 99,
            isSocial = false, isSelfTest = true),
        // The instrumentation APK is a different package with its own UID, so a
        // notification posted from the test process carries this package name.
        // It is the same self-test channel.
        Source(SELF_TEST_INSTRUMENTATION_PACKAGE, "Private Inbox Self Test (test)", "TEST", 100,
            isSocial = false, isSelfTest = true)
    )

    private val byPackage: Map<String, Source> = sources.associateBy { it.packageName }

    /** All known sources, in filter-bar order. */
    val all: List<Source> get() = sources

    /** Real messaging platforms (excludes the self-test source). */
    val messagingSources: List<Source> get() = sources.filter { !it.isSelfTest }

    /** The filter bar shows real platforms only. */
    val filterableSources: List<Source> get() = sources.filter { !it.isSelfTest }

    fun find(packageName: String): Source? = byPackage[packageName]

    fun isKnown(packageName: String): Boolean = byPackage.containsKey(packageName)

    fun displayName(packageName: String): String =
        byPackage[packageName]?.displayName ?: packageName

    fun shortLabel(packageName: String): String =
        byPackage[packageName]?.shortLabel ?: "•"

    /** True when the package belongs to a social network where notifications may be
     *  social activity rather than private messages. */
    fun isSocial(packageName: String): Boolean =
        byPackage[packageName]?.isSocial == true

    /** True when the package is the built-in self-test source. */
    fun isSelfTest(packageName: String): Boolean =
        byPackage[packageName]?.isSelfTest == true

    /**
     * Classify a notification into a [MessageKind].
     *
     * The classifier only uses structural signals that the platform provides:
     *  - MessagingStyle / InboxStyle presence (a message container),
     *  - a conversation title that differs from the app name (a group),
     *  - the notification category,
     *  - a conversation shortcut id (group conversations carry one),
     *  - explicit extras for mentions and replies.
     *
     * Social networks (Instagram, Threads) post both private messages and social
     * activity through the same channel, so their social-activity shape is detected
     * and reported as [MessageKind.SOCIAL_ACTIVITY] rather than being presented as
     * a private chat.
     */
    fun classify(
        packageName: String,
        hasMessageContainer: Boolean,
        conversationTitle: String?,
        appDisplayName: String,
        category: String?,
        hasConversationShortcut: Boolean,
        mentionsOwner: Boolean = false,
        isReply: Boolean = false
    ): MessageKind {
        // Explicit platform-provided signals win first.
        if (mentionsOwner) return MessageKind.MENTION
        if (isReply) return MessageKind.REPLY

        // Social platforms: distinguish private messages from social activity.
        if (isSocial(packageName)) {
            if (!hasMessageContainer) return MessageKind.SOCIAL_ACTIVITY
            // A message container on a social platform is a private message.
            return MessageKind.DIRECT_MESSAGE
        }

        // A distinct conversation title usually means a group room.
        val title = conversationTitle?.trim().orEmpty()
        if (title.isNotEmpty() && !title.equals(appDisplayName, ignoreCase = true) &&
            hasMessageContainer
        ) {
            return MessageKind.GROUP_MESSAGE
        }
        if (hasConversationShortcut && hasMessageContainer) return MessageKind.GROUP_MESSAGE
        if (hasMessageContainer) return MessageKind.DIRECT_MESSAGE

        // Category-driven fallback for plain notifications.
        return when (category) {
            "social" -> MessageKind.SOCIAL_ACTIVITY
            "call" -> MessageKind.DIRECT_MESSAGE
            else -> MessageKind.UNKNOWN
        }
    }
}
