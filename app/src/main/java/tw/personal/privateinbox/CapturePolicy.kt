package tw.personal.privateinbox

/** Has no Android dependencies; test with the standalone Kotlin compiler. */
object CapturePolicy {
    // Explicit source allowlist, not a global notification recorder.
    val knownApps: List<Pair<String, String>> = listOf(
        "LINE" to "jp.naver.line.android",
        "Instagram" to "com.instagram.android",
        "WhatsApp" to "com.whatsapp",
        "Messenger" to "com.facebook.orca",
        "Telegram" to "org.telegram.messenger",
        "Telegram X" to "org.thunderdog.challegram",
        "Teams" to "com.microsoft.teams",
        "KakaoTalk" to "com.kakao.talk"
    )

    fun allowed(packageName: String, selected: Set<String>): Boolean =
        knownApps.any { it.second == packageName } && packageName in selected

    fun normalize(value: String?): String? = value
        ?.trim()
        ?.replace(Regex("[\\t\\r ]+"), " ")
        ?.take(20_000)
        ?.takeIf { it.isNotBlank() }

    fun nameFor(packageName: String): String =
        knownApps.firstOrNull { it.second == packageName }?.first ?: packageName
}
