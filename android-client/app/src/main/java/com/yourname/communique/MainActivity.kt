package com.yourname.communique
import android.provider.MediaStore
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class ChatMessage(val device: String, val message: String, val timestamp: Long, val driveFileId: String? = null, val fileType: String? = null, val fileName: String? = null)

class MainActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private var currentDeviceName = ""
    private var chatHistory = mutableListOf<ChatMessage>()
    private var isPolling = false
    private var isFirstLoad = true
    private val CHANNEL_ID = "communique_chat"
    private var currentSearchQuery = ""
    private var searchMatchIndices = mutableListOf<Int>()
    private var currentSearchIndex = -1
    
    private lateinit var chatMessageContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var userCountText: TextView
    private lateinit var searchPositionText: TextView
    private lateinit var searchIndicatorLayout: LinearLayout
    private lateinit var messageInput: EditText
    
    // NEW: Initialize MediaManager
    private lateinit var mediaManager: MediaManager

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            Toast.makeText(this, "Preparing file for upload...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                handleFileUpload(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize MediaManager
        mediaManager = MediaManager(this, httpClient)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        createNotificationChannel()

        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        currentDeviceName = "$manufacturer ${Build.MODEL}"

        val loginLayout = findViewById<LinearLayout>(R.id.loginLayout)
        val chatLayout = findViewById<RelativeLayout>(R.id.chatLayout)
        val loginTriggerButton = findViewById<Button>(R.id.loginTriggerButton)
        val pinContainer = findViewById<LinearLayout>(R.id.pinContainer)
        val pinInput = findViewById<EditText>(R.id.pinInput)
        val unlockButton = findViewById<Button>(R.id.unlockButton)
        messageInput = findViewById<EditText>(R.id.messageInput)
        val detectedDeviceText = findViewById<TextView>(R.id.detectedDeviceText)
        val gifImageView = findViewById<ImageView>(R.id.loginGif)

        userCountText = findViewById(R.id.userCountText)
        val searchIcon = findViewById<ImageView>(R.id.searchIcon)
        val searchContainer = findViewById<LinearLayout>(R.id.searchContainer)
        val searchInput = findViewById<EditText>(R.id.searchInput)
        val closeSearchBtn = findViewById<ImageButton>(R.id.closeSearchBtn)
        searchIndicatorLayout = findViewById(R.id.searchIndicatorLayout)
        searchPositionText = findViewById(R.id.searchPositionText)
        val searchUpBtn = findViewById<ImageButton>(R.id.searchUpBtn)
        val searchDownBtn = findViewById<ImageButton>(R.id.searchDownBtn)
        val attachButton = findViewById<ImageButton>(R.id.attachButton)

        chatMessageContainer = findViewById(R.id.chatMessageContainer)
        chatScrollView = findViewById(R.id.chatScrollView)

        detectedDeviceText.text = "Device Name: $currentDeviceName"

        try { Glide.with(this).asGif().load(R.drawable.login).into(gifImageView) } catch (e: Exception) {}

        loginTriggerButton.setOnClickListener {
            loginTriggerButton.animate().alpha(0f).setDuration(300).withEndAction {
                loginTriggerButton.visibility = View.GONE
                pinContainer.visibility = View.VISIBLE
                pinContainer.animate().alpha(1f).setDuration(400).start()
            }.start()
        }

        unlockButton.setOnClickListener {
            if (pinInput.text.toString() == "3142") {
                loginLayout.animate().alpha(0f).setDuration(500).withEndAction {
                    loginLayout.visibility = View.GONE
                    chatLayout.visibility = View.VISIBLE
                    chatLayout.animate().alpha(1f).setDuration(600).start()
                    isPolling = true
                    startPollingGist()
                }.start()
            } else {
                Toast.makeText(this, "Incorrect App Lock PIN", Toast.LENGTH_SHORT).show()
            }
        }

        searchIcon.setOnClickListener {
            if (searchContainer.visibility == View.VISIBLE) {
                closeSearch(searchContainer, searchInput)
            } else {
                searchContainer.visibility = View.VISIBLE
                searchInput.requestFocus()
            }
        }

        closeSearchBtn.setOnClickListener { closeSearch(searchContainer, searchInput) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s.toString()
                executeSearch()
            }
        })
        
        searchUpBtn.setOnClickListener {
            if (searchMatchIndices.isNotEmpty()) {
                currentSearchIndex = if (currentSearchIndex > 0) currentSearchIndex - 1 else searchMatchIndices.size - 1
                updateSearchIndicatorAndScroll()
            }
        }

        searchDownBtn.setOnClickListener {
            if (searchMatchIndices.isNotEmpty()) {
                currentSearchIndex = if (currentSearchIndex < searchMatchIndices.size - 1) currentSearchIndex + 1 else 0
                updateSearchIndicatorAndScroll()
            }
        }

        attachButton.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        findViewById<View>(R.id.sendButton).setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                messageInput.text.clear()
                sendMessage(text, null, null)
            }
        }
    }

    private fun sendMessage(rawText: String, driveFileId: String? = null, fileType: String? = null, fileName: String? = null) {
        val encryptedText = encryptMessage(rawText)
        val encryptedFileId = driveFileId?.let { encryptMessage(it) }
        
        val newMessage = ChatMessage(currentDeviceName, encryptedText, System.currentTimeMillis(), encryptedFileId, fileType, fileName)
        chatHistory.add(newMessage)
        CoroutineScope(Dispatchers.Main).launch { updateChatUI() }
        CoroutineScope(Dispatchers.IO).launch { pushGistUpdate(chatHistory) }
    }

    private suspend fun handleFileUpload(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val contentResolver = applicationContext.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                
                var fileName = "file_${System.currentTimeMillis()}"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) fileName = cursor.getString(nameIndex)
                }

                val fileSize = contentResolver.openInputStream(uri)?.available() ?: 0
                if (fileSize > 5 * 1024 * 1024) {
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "File too large (max 5MB)", Toast.LENGTH_LONG).show() }
                    return@withContext
                }

                var fileBytes = contentResolver.openInputStream(uri)?.readBytes() ?: return@withContext
                var finalMimeType = mimeType
                var finalFileName = fileName

                if (mimeType.startsWith("image/")) {
                    try {
                        // FIX: Use different decoding methods based on Android version
                        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            // For Android 9 and above
                            val source = ImageDecoder.createSource(contentResolver, uri)
                            ImageDecoder.decodeBitmap(source)
                        } else {
                            // For Android 8 and below (fixes the Android 7 crash!)
                            @Suppress("DEPRECATION")
                            MediaStore.Images.Media.getBitmap(contentResolver, uri)
                        }

                        val baos = ByteArrayOutputStream()
                        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            @Suppress("DEPRECATION")
                            Bitmap.CompressFormat.WEBP
                        }
                        
                        bitmap.compress(format, 80, baos)
                        fileBytes = baos.toByteArray()
                        finalMimeType = "image/webp"
                        finalFileName = fileName.substringBeforeLast(".") + ".webp"
                    } catch (e: Exception) { 
                        e.printStackTrace() 
                    }
                }

                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Encrypting and Uploading $finalFileName...", Toast.LENGTH_SHORT).show() }

                // USE MEDIAMANAGER TO ENCRYPT
                val encryptedBytes = mediaManager.encryptFileBytes(fileBytes)
                val base64File = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)

                val jsonPayload = JSONObject().apply {
                    put("fileName", finalFileName)
                    put("mimeType", "application/octet-stream")
                    put("fileBase64", base64File)
                }

                val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
                val request = Request.Builder().url(BuildConfig.GAS_UPLOAD_URL).post(requestBody).build()

                httpClient.newCall(request).execute().use { response ->
                    val responseString = response.body?.string()
                    if (response.isSuccessful && responseString != null) {
                        val jsonResponse = JSONObject(responseString)
                        if (jsonResponse.optString("status") == "success") {
                            val fileId = jsonResponse.getString("fileId")
                            val text = messageInput.text.toString().ifEmpty { "Sent an encrypted file" }
                            withContext(Dispatchers.Main) {
                                messageInput.text.clear()
                                Toast.makeText(this@MainActivity, "Upload complete!", Toast.LENGTH_SHORT).show()
                            }
                            sendMessage(text, fileId, finalMimeType, finalFileName)
                        } else {
                            withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "GAS Error: ${jsonResponse.optString("message")}", Toast.LENGTH_LONG).show() }
                        }
                    } else {
                        withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Upload failed (${response.code})", Toast.LENGTH_LONG).show() }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Upload error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun triggerDownload(fileId: String, fileName: String, fileType: String) {
        Toast.makeText(this, "Downloading and decrypting...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            // USE MEDIAMANAGER TO DOWNLOAD AND DECRYPT
            val file = mediaManager.downloadAndDecryptFile(fileId, fileName)
            
            if (file != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity,
                    "${applicationContext.packageName}.provider",
                    file
                )
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, fileType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "No app found to open this file.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to download or decrypt file.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun closeSearch(container: LinearLayout, input: EditText) {
        container.visibility = View.GONE
        currentSearchQuery = ""
        input.text.clear()
        searchMatchIndices.clear()
        currentSearchIndex = -1
        searchIndicatorLayout.visibility = View.GONE
        updateChatUI()
    }

    private fun executeSearch() {
        searchMatchIndices.clear()
        currentSearchIndex = -1
        if (currentSearchQuery.isEmpty()) {
            searchIndicatorLayout.visibility = View.GONE
            updateChatUI()
            return
        }

        for ((index, msg) in chatHistory.withIndex()) {
            if (decryptMessage(msg.message).contains(currentSearchQuery, ignoreCase = true)) {
                searchMatchIndices.add(index)
            }
        }

        if (searchMatchIndices.isNotEmpty()) {
            searchIndicatorLayout.visibility = View.VISIBLE
            currentSearchIndex = searchMatchIndices.size - 1
        } else {
            searchIndicatorLayout.visibility = View.GONE
        }
        updateSearchIndicatorAndScroll()
    }

    private fun updateSearchIndicatorAndScroll() {
        if (searchMatchIndices.isEmpty()) return
        searchPositionText.text = "(${currentSearchIndex + 1}/${searchMatchIndices.size})"
        updateChatUI()
        val targetGlobalIndex = searchMatchIndices[currentSearchIndex]
        val wrapperLayout = chatMessageContainer.getChildAt(targetGlobalIndex)
        if (wrapperLayout != null) {
            chatScrollView.post { chatScrollView.smoothScrollTo(0, wrapperLayout.top) }
        }
    }

    // Keep Text Encryption here in MainActivity
    private fun getSecretKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(BuildConfig.ENCRYPTION_KEY.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptMessage(message: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        return Base64.encodeToString(cipher.doFinal(message.toByteArray(Charsets.UTF_8)), Base64.DEFAULT)
    }

    private fun decryptMessage(encryptedMessage: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey())
            String(cipher.doFinal(Base64.decode(encryptedMessage, Base64.DEFAULT)), Charsets.UTF_8)
        } catch (e: Exception) {
            "🔒 [Decryption Failed]"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Communique Chat", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun showNotification(sender: String, message: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(sender)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
            }
        } else {
            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }

    private fun playNotificationSound() {
        try {
            RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play()
        } catch (e: Exception) {}
    }

    private fun startPollingGist() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isPolling) {
                fetchGist()
                delay(5000)
            }
        }
    }

    private fun fetchGist() {
        val request = Request.Builder()
            .url("https://api.github.com/gists/${BuildConfig.CHAT_GIST_ID}")
            .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body?.string() ?: return
                    val content = JSONObject(responseData).getJSONObject("files").getJSONObject("chat_ledger.json").getString("content")
                    val fetchedHistory: List<ChatMessage> = gson.fromJson(content, object : TypeToken<List<ChatMessage>>() {}.type) ?: emptyList()

                    if (fetchedHistory.size > chatHistory.size) {
                        val lastMessage = fetchedHistory.last()
                        val isMe = lastMessage.device == currentDeviceName

                        chatHistory.clear()
                        chatHistory.addAll(fetchedHistory)

                        CoroutineScope(Dispatchers.Main).launch {
                            updateChatUI()
                            updateUserCount()
                            if (!isFirstLoad && !isMe) {
                                playNotificationSound()
                                showNotification(lastMessage.device, decryptMessage(lastMessage.message))
                            }
                            isFirstLoad = false
                        }
                    } else if (isFirstLoad) {
                        isFirstLoad = false
                        CoroutineScope(Dispatchers.Main).launch { updateUserCount() }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun pushGistUpdate(history: List<ChatMessage>) {
        val payload = JSONObject().apply {
            put("files", JSONObject().apply {
                put("chat_ledger.json", JSONObject().apply {
                    put("content", gson.toJson(history))
                })
            })
        }
        val request = Request.Builder()
            .url("https://api.github.com/gists/${BuildConfig.CHAT_GIST_ID}")
            .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
            .patch(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            httpClient.newCall(request).execute().close()
        } catch (e: Exception) {}
    }

    private fun updateUserCount() {
        ChatUIHelper.updateUserCountBar(this, userCountText, chatHistory)
    }

    private fun updateChatUI() {
        chatMessageContainer.removeAllViews()

        // Identify the last 2 images for auto-download
        val imageIndices = chatHistory.indices.filter { chatHistory[it].fileType?.startsWith("image/") == true }
        val autoDownloadIndices = imageIndices.takeLast(2)

        for ((index, msg) in chatHistory.withIndex()) {
            val decryptedText = decryptMessage(msg.message)
            val decryptedFileId = msg.driveFileId?.let { decryptMessage(it) }
            val isFocusedMatch = searchMatchIndices.isNotEmpty() && currentSearchIndex >= 0 && searchMatchIndices[currentSearchIndex] == index
            val isAutoDownload = index in autoDownloadIndices

            // Delegate the UI building to our new helper file
            val bubbleView = ChatUIHelper.buildMessageBubble(
                context = this,
                msg = msg,
                currentDeviceName = currentDeviceName,
                isAutoDownload = isAutoDownload,
                decryptedText = decryptedText,
                decryptedFileId = decryptedFileId,
                currentSearchQuery = currentSearchQuery,
                isFocusedSearchMatch = isFocusedMatch,
                mediaManager = mediaManager
            ) { fileId, fileName, fileType ->
                triggerDownload(fileId, fileName, fileType)
            }

            chatMessageContainer.addView(bubbleView)
        }

        // Auto-scroll to bottom if not searching
        if (currentSearchQuery.isEmpty() && chatMessageContainer.childCount > 0) {
            chatScrollView.post { chatScrollView.smoothScrollTo(0, chatMessageContainer.bottom) }
        }
    }
}
