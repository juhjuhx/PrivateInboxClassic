package tw.personal.privateinbox

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Payloads encrypted independently using Android Keystore AES-256-GCM.
 * Authentication is handled at UI entry via the device credential. The write key must
 * remain available while the app is in the background to capture incoming messages.
 */
object CryptoStore {
    private const val PROVIDER = "AndroidKeyStore"

    /**
     * The key aliases.
     *
     * They are public so the recovery flow can report on them. The alias
     * itself is not a secret: it is the NAME of a key inside the Keystore, and
     * the key material never leaves the Keystore.
     */
    const val AES_ALIAS = "private-inbox-aes-v1"
    const val HMAC_ALIAS = "private-inbox-hmac-v1"

    @Synchronized
    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                AES_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    private fun hmacKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(HMAC_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                HMAC_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKey()
    }

    fun seal(packageName: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        cipher.updateAAD(packageName.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext)
        return cipher.iv + encrypted
    }

    fun open(packageName: String, blob: ByteArray): ByteArray {
        require(blob.size >= 12 + 16) { "Invalid encrypted payload" }
        val iv = blob.copyOfRange(0, 12)
        val ciphertext = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(packageName.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext)
    }

    /**
     * The state of the encryption key, as observed by this process.
     *
     * The distinction matters: an invalidated key is permanent (the platform
     * will never give that key back), while a temporarily unavailable key may
     * become usable again — for example when the device has not been unlocked
     * since a reboot. Silently treating both as "decryption failed" would make
     * the app delete recoverable data.
     */
    enum class KeyState {
        /** The key is present and usable. */
        KEY_AVAILABLE,

        /** The key exists but cannot be used right now (device locked, or the
         *  keystore is temporarily unavailable). Retrying later may work. */
        KEY_TEMPORARILY_UNAVAILABLE,

        /** The key was permanently invalidated (screen lock changed, biometric
         *  re-enrolled, factory reset of the keystore). The existing ciphertext
         *  can never be decrypted again. */
        KEY_INVALIDATED,

        /** A decryption attempt failed for a reason other than the key state
         *  (corrupt row, tampered data, wrong AAD). */
        DECRYPT_FAILED,

        /** Recovery is required: the key is gone and the stored data cannot be
         *  read. The user must be told and must explicitly choose what to do
         *  with the unreadable rows. */
        RECOVERY_REQUIRED;

        /** True when existing ciphertext is permanently unreadable. */
        val isUnrecoverable: Boolean
            get() = this == KEY_INVALIDATED || this == RECOVERY_REQUIRED
    }

    /** The current key state, without attempting any decryption. */
    fun keyState(): KeyState = try {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = ks.getKey(AES_ALIAS, null)
        when {
            key == null -> KeyState.KEY_INVALIDATED
            key !is SecretKey -> KeyState.KEY_INVALIDATED
            else -> {
                // Force the key to be materialised: a key that exists but cannot
                // be used reports a temporary failure here.
                try {
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.ENCRYPT_MODE, key)
                    KeyState.KEY_AVAILABLE
                } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                    KeyState.KEY_INVALIDATED
                } catch (e: java.security.KeyStoreException) {
                    KeyState.KEY_TEMPORARILY_UNAVAILABLE
                } catch (e: Exception) {
                    KeyState.KEY_TEMPORARILY_UNAVAILABLE
                }
            }
        }
    } catch (e: java.security.KeyStoreException) {
        // The keystore itself is unavailable (for example before first unlock).
        KeyState.KEY_TEMPORARILY_UNAVAILABLE
    } catch (e: Exception) {
        KeyState.KEY_TEMPORARILY_UNAVAILABLE
    }

    /**
     * Decrypt a sealed payload, reporting the reason on failure.
     *
     * The caller must distinguish "this row is corrupt" from "the key is gone",
     * because only the latter means every existing row is unreadable.
     */
    fun openWithState(packageName: String, blob: ByteArray): Result {
        if (blob.size < 12 + 16) return Result(KeyState.DECRYPT_FAILED, null)
        return try {
            val iv = blob.copyOfRange(0, 12)
            val ciphertext = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(packageName.toByteArray(Charsets.UTF_8))
            Result(KeyState.KEY_AVAILABLE, cipher.doFinal(ciphertext))
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            Result(KeyState.KEY_INVALIDATED, null)
        } catch (e: java.security.UnrecoverableKeyException) {
            Result(KeyState.KEY_INVALIDATED, null)
        } catch (e: java.security.KeyStoreException) {
            Result(KeyState.KEY_TEMPORARILY_UNAVAILABLE, null)
        } catch (e: java.security.GeneralSecurityException) {
            // GCM tag mismatch: the ciphertext is wrong, the key is fine.
            Result(KeyState.DECRYPT_FAILED, null)
        } catch (e: Exception) {
            Result(KeyState.DECRYPT_FAILED, null)
        }
    }

    /** A decryption outcome: the key state and, on success, the plaintext. */
    data class Result(val state: KeyState, val plaintext: ByteArray?)

    /**
     * HMAC avoids storing a reversible low-entropy raw message hash in SQLite.
     *
     * @param discriminator an extra per-message component, used only when the
     *        source provides no timestamp. It keeps two DISTINCT messages apart
     *        when their text is identical, while staying stable for the same
     *        message across revisions of the same notification.
     *
     * When the discriminator is empty the hashed input is byte-identical to the
     * historical format, so rows written by an earlier version keep their
     * fingerprint and are not captured a second time after an update.
     */
    fun fingerprint(packageName: String, timestamp: Long, title: String, text: String,
                    discriminator: String = ""): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey())
        return mac.doFinal(fingerprintInput(packageName, timestamp, title, text, discriminator))
    }

    /**
     * The exact bytes that are hashed to produce a message fingerprint.
     *
     * This is a pure function, so the dedup semantics can be tested on the
     * JVM where the Android Keystore is not available.
     *
     * The format is `package \0 timestamp \0 title \0 text [\0 discriminator]`.
     * The discriminator is appended ONLY when it is not empty, so a message
     * whose source provided a timestamp keeps the historical fingerprint and
     * is not captured a second time after an update.
     */
    fun fingerprintInput(packageName: String, timestamp: Long, title: String,
                         text: String, discriminator: String = ""): ByteArray {
        val base = "$packageName\u0000$timestamp\u0000$title\u0000$text"
        val data = if (discriminator.isEmpty()) base else "$base\u0000$discriminator"
        return data.toByteArray(Charsets.UTF_8)
    }
}
