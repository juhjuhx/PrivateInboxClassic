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
    private const val AES_ALIAS = "private-inbox-aes-v1"
    private const val HMAC_ALIAS = "private-inbox-hmac-v1"

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

    /** HMAC avoids storing a reversible low-entropy raw message hash in SQLite. */
    fun fingerprint(packageName: String, timestamp: Long, title: String, text: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey())
        val data = "$packageName\u0000$timestamp\u0000$title\u0000$text"
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }
}
