package tw.personal.privateinbox

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException

/**
 * The user's-facing recovery flow for a lost encryption key.
 *
 * The rules this enforces:
 *
 *  1. **Nothing is deleted automatically.** An invalidated key is permanent,
 *     but a key that is only temporarily unavailable (the device has not been
 *     unlocked yet) must never destroy anything.
 *  2. **A temporary lock is not a loss.** The two states are reported
 *     differently and only the permanent one offers to delete.
 *  3. **Deleting requires an explicit, informed confirmation.** The dialog
 *     states exactly how many messages are affected and that the deletion
 *     cannot be undone.
 *  4. **A new key never silently overwrites old data.** The key is only
 *     created when there is no data to protect, or after the user has
 *     explicitly chosen to start over.
 */
object KeyRecovery {

    /**
     * What the user should be told about the key's state.
     *
     * @param state the key state
     * @param unreadableRows how many stored messages cannot be decrypted
     * @param totalRows how many messages are stored in total
     */
    fun describe(state: CryptoStore.KeyState, unreadableRows: Int, totalRows: Int): Description {
        return when (state) {
            CryptoStore.KeyState.KEY_AVAILABLE ->
                Description(
                    title = "加密金鑰正常",
                    message = "所有訊息都可以解密。",
                    action = Action.NONE
                )

            CryptoStore.KeyState.KEY_TEMPORARILY_UNAVAILABLE ->
                Description(
                    title = "加密金鑰暫時無法使用",
                    message = buildString {
                        append("裝置尚未解鎖，Android 暫時不允許使用加密金鑰。\n\n")
                        append("這是暫時的狀況，資料沒有損壞。把裝置解鎖後重新開啟本 App 即可。\n\n")
                        if (totalRows > 0) {
                            append("目前有 $totalRows 則已保存的訊息，解鎖後就會顯示。")
                        }
                    },
                    action = Action.NONE
                )

            CryptoStore.KeyState.DECRYPT_FAILED ->
                Description(
                    title = "部分訊息無法解密",
                    message = buildString {
                        append("有 $unreadableRows 則訊息無法解密，其餘 $totalRows 則正常。\n\n")
                        append("這通常是該則訊息在寫入時就被破壞，而不是金鑰的問題。\n\n")
                        append("受影響的訊息會被保留在資料庫中，不會被自動刪除。")
                    },
                    action = Action.NONE
                )

            CryptoStore.KeyState.KEY_INVALIDATED ->
                Description(
                    title = "加密金鑰已失效",
                    message = buildString {
                        append("Android 撤銷了這把金鑰（常見原因：變更螢幕鎖設定、重新註冊生物辨識）。\n\n")
                        append("金鑰無法復原 —— 重新設定螢幕鎖也不會讓舊資料回來。\n\n")
                        append("受影響的 $unreadableRows 則訊息已永久無法解密。")
                    },
                    action = Action.ASK_ABOUT_CLEARING
                )

            CryptoStore.KeyState.RECOVERY_REQUIRED ->
                Description(
                    title = "需要處理：加密金鑰已失效",
                    message = buildString {
                        append("加密金鑰已失效（例如變更螢幕鎖或重新註冊生物辨識）。\n\n")
                        append("受影響的 $unreadableRows 則訊息已永久無法解密。")
                        append("金鑰無法復原 —— 重新設定螢幕鎖也不會讓舊資料回來。\n\n")
                        append("這些資料仍留在本機。你可以先保留它們，或明確清除它們並重新開始。")
                    },
                    action = Action.ASK_ABOUT_CLEARING
                )
        }
    }

    /** What the recovery flow offers the user. */
    enum class Action { NONE, ASK_ABOUT_CLEARING }

    /** The text shown to the user, and what may be offered. */
    data class Description(
        val title: String,
        val message: String,
        val action: Action
    )

    /**
     * The diagnosis of the key's state, for the diagnostics panel.
     *
     * This reports the KEYSTORE's own view of the key, which can be more
     * precise than the state inferred from a decryption attempt.
     */
    fun diagnose(context: Context): String {
        val store = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }.getOrNull() ?: return "keystore=unavailable"

        val aliases = listOf(CryptoStore.AES_ALIAS, CryptoStore.HMAC_ALIAS)
        val parts = mutableListOf<String>()
        parts += "provider=AndroidKeyStore"
        parts += "deviceLocked=${isDeviceLocked(context)}"
        for (alias in aliases) {
            val present = runCatching { store.containsAlias(alias) }.getOrDefault(false)
            val usable = if (!present) {
                "absent"
            } else {
                runCatching {
                    store.getKey(alias, null)
                    when (val error = runCatching {
                        store.getEntry(alias, null)
                    }.exceptionOrNull()) {
                        null -> "present"
                        is UnrecoverableKeyException -> "unrecoverable"
                        else -> "present"
                    }
                }.getOrDefault("error")
            }
            parts += "${alias.substringAfterLast('.')}=$usable"
        }
        // Whether the key can actually be USED right now. This is the check
        // that distinguishes "temporarily unavailable" from "invalidated".
        val usableNow = runCatching {
            val key = store.getKey(CryptoStore.AES_ALIAS, null)
            val cipher = javax.crypto.Cipher.getInstance(
                "AES/GCM/NoPadding"
            )
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            true
        }.getOrDefault(false)
        parts += "usableNow=$usableNow"
        parts += "secureLock=${isDeviceLocked(context)}"
        return parts.joinToString(" ")
    }

    /** True when the device is currently locked. */
    fun isDeviceLocked(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 23) {
            val state = android.app.KeyguardManager::class.java
                .let { context.getSystemService(it) }
                ?.isDeviceLocked ?: false
            state
        } else {
            false
        }
    }.getOrDefault(false)

    /**
     * The confirmation shown before anything is deleted.
     *
     * It is deliberately a SECOND dialog: the first one explains the state,
     * this one is the explicit, informed consent. The destructive button is
     * worded so that it says exactly what happens.
     */
    fun confirmClear(
        activity: android.app.Activity,
        unreadableRows: Int,
        onConfirm: () -> Unit
    ) {
        val message = buildString {
            append("即將刪除 $unreadableRows 則無法解密的訊息。\n\n")
            append("這個動作無法復原。")
            append("可解密的訊息不會受到影響。\n\n")
            append("確定要清除嗎？")
        }
        AlertDialog.Builder(activity)
            .setTitle("確認清除無法解密的資料")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("清除 $unreadableRows 則") { _, _ -> onConfirm() }
            .show()
    }
}
