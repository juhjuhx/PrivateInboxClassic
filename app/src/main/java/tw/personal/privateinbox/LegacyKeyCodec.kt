package tw.personal.privateinbox

/**
 * The historical conversation-key format, parsed without ever rewriting it.
 *
 * v0.4.5 stored `messages.conversation_key` as:
 *
 * ```
 * package \0 (shortcutId?.takeIf { it.isNotBlank() } ?: title) \0 title
 * ```
 *
 * That value is an INPUT to the dedup fingerprint
 * ([CryptoStore.fingerprintInput]), so it can never be rewritten — doing so
 * would change the fingerprint of every existing row and silently break
 * deduplication and the DHC identity.
 *
 * v0.4.6 therefore only ever READS this format, to back-fill alias rows. When
 * the format does not unambiguously identify a conversation, the parse reports
 * [Parsed.ambiguous] instead of guessing. Guessing is what produced the original
 * bug (one chat split in two when a title changed).
 */
object LegacyKeyCodec {

    /** The separator v0.4.5 used between the three parts. */
    private const val SEP = '\u0000'

    /**
     * One parsed legacy key.
     *
     * @property packageName first segment, always present (may be empty for a
     *   malformed key)
     * @property middle the `shortcutId ?: title` segment
     * @property title the trailing display title
     * @property ambiguous true when the key cannot be interpreted without
     *   guessing — extra separators, missing segments, or a middle part that is
     *   only the repeated title
     */
    data class Parsed(
        val packageName: String,
        val middle: String,
        val title: String,
        val ambiguous: Boolean
    ) {
        /**
         * True when the middle segment can be trusted as a real shortcut id.
         *
         * A middle equal to the title is the historical *fallback*: the source
         * supplied no shortcut, so the title was repeated into the middle slot.
         * Treating that as a stable identifier would re-create the bug where a
         * renamed conversation is treated as a new one (and, worse, where two
         * same-named groups are treated as one).
         */
        val middleLooksLikeShortcut: Boolean
            get() = !ambiguous && middle.isNotEmpty() && middle != title
    }

    /** Build the historical key exactly as v0.4.5 did. */
    fun format(packageName: String, discriminator: String?, title: String): String {
        val middle = discriminator?.takeIf { it.isNotBlank() } ?: title
        return buildString {
            append(packageName)
            append(SEP)
            append(middle)
            append(SEP)
            append(title)
        }
    }

    /**
     * Parse a stored key.
     *
     * Never throws: a malformed key yields an [Parsed] with `ambiguous = true`
     * and whatever could be recovered. A crash here would happen during
     * migration, which is the worst possible time.
     */
    fun parse(key: String): Parsed {
        if (key.isEmpty()) {
            return Parsed("", "", "", ambiguous = true)
        }
        val parts = key.split(SEP)
        if (parts.size < 3) {
            // Missing segments: keep the package, flag the rest.
            return Parsed(
                packageName = parts.firstOrNull().orEmpty(),
                middle = parts.getOrNull(1).orEmpty(),
                title = "",
                ambiguous = true
            )
        }
        if (parts.size > 3) {
            // A title that contained the separator cannot round-trip. Do not
            // silently pick a split point.
            return Parsed(
                packageName = parts[0],
                middle = parts[1],
                title = parts.drop(2).joinToString(SEP.toString()),
                ambiguous = true
            )
        }
        val pkg = parts[0]
        val middle = parts[1]
        val title = parts[2]
        // A middle equal to the title is the historical FALLBACK (the source
        // supplied no shortcut and the title was repeated into that slot).
        // That is not a stable identity, so the key is AMBIGUOUS: the resolver
        // must never treat a title-only key as a strong shortcut.
        val ambiguous = middle.isEmpty() || middle == title
        return Parsed(pkg, middle, title, ambiguous)
    }
}
