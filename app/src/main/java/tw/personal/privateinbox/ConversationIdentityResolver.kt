package tw.personal.privateinbox

/** Which kind of signal an alias was registered from. */
enum class AliasType {
    /** A platform conversation shortcut id — a strong, per-package identity. */
    SHORTCUT,

    /** The historical `conversation_key` value stored in `messages`. */
    LEGACY_KEY,

    /** A merge or association the user performed explicitly. */
    MANUAL
}

/** The outcome of registering an alias. */
enum class AliasPutResult {
    /** A new alias row was created. */
    INSERTED,
    /** The alias already pointed at the same conversation. */
    ALREADY_SAME,
    /** The alias belongs to a DIFFERENT conversation; the caller must resolve it. */
    CONFLICT
}

/**
 * Where the identity evidence came from, and how much it can be trusted.
 *
 * The previous two-value enum (`STRONG` / `WEAK`) conflated two different
 * questions: "what kind of signal is this?" and "how much does it prove?". A
 * shortcut id read out of a HISTORICAL key is not the same evidence as a
 * shortcut id the platform just handed us — the first is an inference about a
 * string, the second is a fact the platform asserted.
 *
 * The stored `strength` column keeps its historical values for compatibility
 * (see [legacyValue]); new rows use the richer names.
 */
enum class Strength {
    /** The platform asserted this shortcut id for this notification. */
    PLATFORM_CONFIRMED,

    /** Derived by parsing a stored legacy key. Format alone proves nothing. */
    LEGACY_INFERRED,

    /** Only a display title — can change and can collide. */
    TITLE_ONLY,

    /** The user explicitly associated these conversations. */
    MANUAL_CONFIRMED,

    /** Not enough evidence to say; must be presented as uncertain. */
    AMBIGUOUS;

    /**
     * The value written to the `strength` column.
     *
     * `STRONG` / `WEAK` are the values older rows already contain, and the
     * resolver still has to read those, so nothing is renamed in place. New
     * writes use the taxonomy name, and [fromStored] maps both.
     */
    val storedValue: String
        get() = when (this) {
            PLATFORM_CONFIRMED, MANUAL_CONFIRMED -> "STRONG"   // historical value
            LEGACY_INFERRED -> "LEGACY_INFERRED"
            TITLE_ONLY, AMBIGUOUS -> "WEAK"                    // historical value
        }

    /** True when this evidence may be treated as a certain identity. */
    val isStrong: Boolean
        get() = this == PLATFORM_CONFIRMED || this == MANUAL_CONFIRMED

    companion object {
        /** Parse a stored value, tolerating the historical two-value form. */
        fun fromStored(raw: String?): Strength = when (raw) {
            "PLATFORM_CONFIRMED" -> PLATFORM_CONFIRMED
            "LEGACY_INFERRED" -> LEGACY_INFERRED
            "TITLE_ONLY" -> TITLE_ONLY
            "MANUAL_CONFIRMED" -> MANUAL_CONFIRMED
            "AMBIGUOUS" -> AMBIGUOUS
            // Historical values. STRONG was used for both a real platform
            // shortcut and a manual merge, so it maps to the conservative of the
            // two that is still certain: PLATFORM_CONFIRMED is the one that a
            // SHORTCUT alias could have come from.
            "STRONG" -> PLATFORM_CONFIRMED
            "WEAK" -> TITLE_ONLY
            else -> AMBIGUOUS
        }
    }
}

/** The persistence seam for aliases; implemented over SQLite in production. */
interface AliasLookup {
    fun find(pkg: String, type: AliasType, value: String): String?
    fun put(pkg: String, type: AliasType, value: String, conversationId: String, strength: Strength)
    fun newConversationId(): String
}

/**
 * One resolution result.
 *
 * @property conversationId the stable canonical id (opaque, never derived from
 *   mutable data)
 * @property legacyKey the historical key, verbatim — the fingerprint input
 * @property ambiguous true when the identity rests on weak evidence and the UI
 *   must present it as uncertain
 * @property strength STRONG only when a real shortcut (or a user action) backed it
 * @property matchedBy which signal produced the match
 */
data class Resolution(
    val conversationId: String,
    val legacyKey: String,
    val ambiguous: Boolean,
    val strength: Strength,
    val matchedBy: AliasType
)

/**
 * Resolves a notification to a stable canonical conversation id.
 *
 * ## Why this exists
 *
 * v0.4.5 built `ConversationIdentity.key` as `pkg \0 shortcut \0 title` and used
 * it for grouping. Because the title was appended unconditionally, renaming a
 * contact split one conversation into two boxes. When no shortcut was supplied
 * the middle slot fell back to the title, so two same-named groups collapsed
 * into one.
 *
 * ## What it deliberately does NOT do
 *
 * - It never merges two conversations on the strength of a title alone.
 * - It never derives the canonical id from a hash of mutable data; ids are
 *   allocated once and mapped, so a rename cannot change them.
 * - It never changes the legacy key, because that value is the dedup
 *   fingerprint's input and rewriting it would break deduplication for every
 *   existing row.
 */
class ConversationIdentityResolver(private val aliases: AliasLookup) {

    fun resolve(packageName: String, shortcutId: String?, title: String): Resolution {
        val strong = shortcutId?.takeIf { it.isNotBlank() }
        val legacyKey = LegacyKeyCodec.format(packageName, strong, title)

        if (strong != null) {
            // 1a. an existing shortcut alias wins.
            //
            // The legacy key is STILL registered against that conversation. A
            // rename produces a new legacy key for the same shortcut, and that
            // key must be recorded so a later back-fill (or a DHC import that
            // only knows the old key) can find its way back to this
            // conversation. Registering is idempotent.
            aliases.find(packageName, AliasType.SHORTCUT, strong)?.let { cid ->
                aliases.put(packageName, AliasType.LEGACY_KEY, legacyKey, cid,
                    Strength.PLATFORM_CONFIRMED)
                return Resolution(cid, legacyKey, ambiguous = false,
                    strength = Strength.PLATFORM_CONFIRMED, matchedBy = AliasType.SHORTCUT)
            }
            // 1b. adopt a conversation that already owns this legacy key
            //     (a v0.4.5 row stored under this exact key)
            val adopted = aliases.find(packageName, AliasType.LEGACY_KEY, legacyKey)
            val cid = adopted ?: aliases.newConversationId()
            // The shortcut came from the PLATFORM for this notification, so this
            // is confirmed evidence — unlike a shortcut parsed out of a stored key.
            aliases.put(packageName, AliasType.SHORTCUT, strong, cid,
                Strength.PLATFORM_CONFIRMED)
            aliases.put(packageName, AliasType.LEGACY_KEY, legacyKey, cid,
                Strength.PLATFORM_CONFIRMED)
            return Resolution(cid, legacyKey, ambiguous = false,
                strength = Strength.PLATFORM_CONFIRMED,
                matchedBy = if (adopted != null) AliasType.LEGACY_KEY else AliasType.SHORTCUT)
        }

        // 2. no strong identity: a title is weak evidence.
        aliases.find(packageName, AliasType.LEGACY_KEY, legacyKey)?.let { cid ->
            return Resolution(cid, legacyKey, ambiguous = true,
                strength = Strength.TITLE_ONLY, matchedBy = AliasType.LEGACY_KEY)
        }
        val cid = aliases.newConversationId()
        aliases.put(packageName, AliasType.LEGACY_KEY, legacyKey, cid, Strength.TITLE_ONLY)
        return Resolution(cid, legacyKey, ambiguous = true,
            strength = Strength.TITLE_ONLY, matchedBy = AliasType.LEGACY_KEY)
    }
}
