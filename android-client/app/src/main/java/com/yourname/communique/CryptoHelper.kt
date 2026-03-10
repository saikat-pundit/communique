package com.yourname.communique

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {

    private fun getSecretKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val realKey = SecretDecoder.decode(BuildConfig.ENCRYPTION_KEY) // DECODE HERE
        val keyBytes = digest.digest(realKey.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }
    fun encrypt(message: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        return Base64.encodeToString(cipher.doFinal(message.toByteArray(Charsets.UTF_8)), Base64.DEFAULT)
    }

    fun decrypt(encryptedMessage: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey())
            String(cipher.doFinal(Base64.decode(encryptedMessage, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (e: Exception) {
            "🔒 [Decryption Failed]"
        }
    }
}
