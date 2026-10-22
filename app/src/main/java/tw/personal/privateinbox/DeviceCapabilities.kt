package tw.personal.privateinbox

import android.os.Build

/**
 * What the device can actually do, discovered at runtime.
 *
 * The app is not written for one phone. Different OEMs ship different
 * notification behaviour, and the differences are discovered by LOOKING at
 * what the platform provides, not by hard-coding a device list.
 *
 * The three layers are:
 *
 *  1. ANDROID FRAMEWORK — the API contract. Stable, and the only layer the
 *     app may depend on.
 *  2. CAPABILITY DETECTION — what this particular device and this particular
 *     Android version actually provide.
 *  3. OEM GUIDANCE — what the user can do about it, when a device's background
 *     policy interferes. Advice only; the app never changes a system setting.
 */
object DeviceCapabilities {

    /** The Android version, as a comparable integer. */
    val androidVersion: Int get() = Build.VERSION.SDK_INT

    /** The API level, for the cases that need it by name. */
    val apiLevel: Int get() = Build.VERSION.SDK_INT

    /** The manufacturer, lower-cased. Empty when it is not known. */
    val manufacturer: String get() = (Build.MANUFACTURER ?: "").lowercase()

    /** The device model. */
    val model: String get() = Build.MODEL ?: ""

    /** The OEM family this device belongs to, for the guidance layer. */
    enum class OemFamily {
        /** Google Pixel and the AOSP-based builds. */
        AOSP,

        /** Samsung One UI. */
        SAMSUNG,

        /** Xiaomi MIUI and HyperOS. */
        XIAOMI,

        /** vivo OriginOS and Funtouch OS. */
        VIVO,

        /** OPPO ColorOS. */
        OPPO,

        /** realme UI, which is a ColorOS derivative. */
        REALME,

        /** HONOR MagicOS. */
        HONOR,

        /** Huawei EMUI and HarmonyOS. */
        HUAWEI,

        /** Motorola's near-stock Android. */
        MOTOROLA,

        /** Anything else. */
        OTHER
    }

    /**
     * The OEM family.
     *
     * It is DERIVED from the manufacturer string, and it falls back to OTHER.
     * An unknown manufacturer is never guessed into a known family, because
     * giving the wrong OEM advice is worse than giving none.
     */
    val oemFamily: OemFamily
        get() = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("poco") -> OemFamily.XIAOMI
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
                OemFamily.VIVO
            manufacturer.contains("samsung") -> OemFamily.SAMSUNG
            manufacturer.contains("oppo") -> OemFamily.OPPO
            manufacturer.contains("realme") -> OemFamily.REALME
            manufacturer.contains("honor") -> OemFamily.HONOR
            manufacturer.contains("huawei") -> OemFamily.HUAWEI
            manufacturer.contains("motorola") -> OemFamily.MOTOROLA
            manufacturer.contains("google") || manufacturer.contains("aosp") ||
                manufacturer.contains("android") -> OemFamily.AOSP
            else -> OemFamily.OTHER
        }

    /**
     * True when the device is a Chinese-market build.
     *
     * This matters because the Chinese ROMs ship their own background policy
     * and, in the Chinese builds, Google Play Services may be absent. The app
     * must not DEPEND on Play Services, and the guidance has to be the
     * Chinese-market one.
     */
    val isChineseRom: Boolean
        get() = oemFamily in setOf(
            OemFamily.XIAOMI, OemFamily.VIVO, OemFamily.OPPO,
            OemFamily.REALME, OemFamily.HONOR, OemFamily.HUAWEI
        )

    // ---- capability detection -------------------------------------------

    /**
     * True when the platform caps the MessagingStyle history.
     *
     * `Notification.MAX_MESSAGES` is 25 on every platform version this app
     * supports, so a notification NEVER carries more than 25 messages. That is
     * a platform limit, not a limit of this app, and it is the reason a burst
     * beyond 25 messages in ONE revision cannot be captured by ANY listener.
     */
    val messagingHistoryIsCapped: Boolean get() = true

    /** The platform's MessagingStyle history cap. */
    const val MESSAGING_HISTORY_LIMIT = 25

    /**
     * True when the notification listener can be re-bound on demand.
     *
     * `NotificationListenerService.requestRebind` is API 24+. On every version
     * this app supports it is available, and the user-facing "re-check
     * connection" action uses it.
     */
    val canRequestRebind: Boolean get() = Build.VERSION.SDK_INT >= 24

    /**
     * True when the app can post its own notifications.
     *
     * POST_NOTIFICATIONS is a runtime permission from API 33. Below that the
     * permission is implicit.
     */
    val canPostNotifications: Boolean get() = Build.VERSION.SDK_INT < 33

    /**
     * True when the system can tell the app that a notification was removed.
     *
     * `onNotificationRemoved` is API 18+, and the app uses it to release the
     * per-notification memory of which messages were already written.
     */
    val reportsNotificationRemoval: Boolean get() = Build.VERSION.SDK_INT >= 18

    /**
     * True when the device is in a state where the background policy is likely
     * to interfere.
     *
     * This is a HEURISTIC, and it is only used to decide whether to SHOW
     * the guidance. The app never changes a system setting, and the guidance
     * is always shown for the OEM families that are known to have one.
     */
    val hasAggressiveBackgroundPolicy: Boolean
        get() = oemFamily in setOf(
            OemFamily.XIAOMI, OemFamily.VIVO, OemFamily.OPPO,
            OemFamily.REALME, OemFamily.HONOR, OemFamily.HUAWEI
        )

    /**
     * The guidance for this device, or null when there is nothing to say.
     *
     * It is ADVICE. The app cannot grant itself the settings it needs, and it
     * must not try to: the user decides.
     */
    fun backgroundGuidance(): String? {
        if (!hasAggressiveBackgroundPolicy) return null
        return when (oemFamily) {
            OemFamily.XIAOMI ->
                "小米／紅米／POCO：MIUI 與 HyperOS 會在背景限制 App。" +
                    "請到「設定 → 應用程式管理 → 右上角選單 → 應用程式管理 → " +
                    "自啟動管理」開啟本程式，並在「省電 → 後台耗電管理」設為「無限制」。"

            OemFamily.VIVO ->
                "vivo：OriginOS 與 Funtouch OS 會在背景限制 App。" +
                    "請到「設定 → 電池 → 後台耗電管理」開啟本程式，" +
                    "並允許「自啟動」，同時在最近任務畫面把本程式鎖定。"

            OemFamily.OPPO ->
                "OPPO：ColorOS 會在背景限制 App。" +
                    "請到「設定 → 電池 → 後台耗電允許」允許本程式，" +
                    "並在「權限管理 → 允許自啟動」開啟。"

            OemFamily.REALME ->
                "realme：UI 與 ColorOS 相同，請到「設定 → 電池 → 後台耗電允許」" +
                    "允許本程式，並允許自啟動。"

            OemFamily.HONOR ->
                "HONOR：MagicOS 會在背景限制 App。請到「設定 → 電池 → 啟動管理」" +
                    "允許本程式自動啟動與關聯啟動。"

            OemFamily.HUAWEI ->
                "華為／榮耀：EMUI 與 HarmonyOS 會在背景限制 App，" +
                    "且中國版沒有 Google Play Services —— 本程式不需要它。" +
                    "請到「設定 → 應用程式管理 → 啟動管理」允許本程式。"

            else -> null
        }
    }

    /**
     * A one-line description of the environment, for the diagnostics export.
     *
     * It contains no private data: the model and the OS version are the
     * things a support report legitimately needs.
     */
    fun describe(): String =
        "android=$apiLevel oem=${oemFamily.name} model=$model chinese_rom=$isChineseRom " +
            "history_cap=$MESSAGING_HISTORY_LIMIT"
}
