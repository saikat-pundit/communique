package com.yourname.communique

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.ImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class MediaManager(private val context: Context, private val httpClient: OkHttpClient) {

    private fun getSecretKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(BuildConfig.ENCRYPTION_KEY.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptFileBytes(fileBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        return cipher.doFinal(fileBytes)
    }

    fun decryptFileBytes(encryptedBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey())
        return cipher.doFinal(encryptedBytes)
    }

    // Downloads from GAS Proxy, caches it, and decrypts it
    suspend fun downloadAndDecryptFile(fileId: String, fileName: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val mediaDir = File(context.cacheDir, "decrypted_media")
                if (!mediaDir.exists()) mediaDir.mkdirs()
                val file = File(mediaDir, fileName)

                // If file already exists in cache, skip downloading! (Great for fast thumbnails)
                if (file.exists() && file.length() > 0) return@withContext file

                // Request the file from your Google Apps Script
                val url = "${BuildConfig.GAS_UPLOAD_URL}?fileId=$fileId"
                val request = Request.Builder().url(url).build()

                httpClient.newCall(request).execute().use { response ->
                    val respString = response.body?.string() ?: return@withContext null
                    val jsonResponse = JSONObject(respString)
                    
                    if (jsonResponse.optString("status") == "success") {
                        val base64Data = jsonResponse.getString("fileBase64")
                        val encryptedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                        
                        // Decrypt and save
                        val decryptedBytes = decryptFileBytes(encryptedBytes)
                        file.writeBytes(decryptedBytes)
                        return@withContext file
                    }
                }
                null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    // Loads the decrypted image into an ImageView for thumbnails
    suspend fun loadThumbnail(fileId: String, fileName: String, imageView: ImageView) {
        val file = downloadAndDecryptFile(fileId, fileName)
        if (file != null) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            withContext(Dispatchers.Main) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }
}
