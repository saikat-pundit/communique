package com.yourname.communique
import org.json.JSONArray
import kotlinx.coroutines.withContext
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
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
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
                currentSearchIndex = if (currentSearchIndex > 0) {
                    currentSearchIndex - 1
                } else {
                    searchMatchIndices.size - 1
                }
                updateSearchIndicatorAndScroll()
            }
        }

        searchDownBtn.setOnClickListener {
            if (searchMatchIndices.isNotEmpty()) {
                currentSearchIndex = if (currentSearchIndex < searchMatchIndices.size - 1) {
                    currentSearchIndex + 1
                } else {
                    0
                }
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
    private fun encryptFileBytes(fileBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        return cipher.doFinal(fileBytes)
    }
    private fun decryptFileBytes(encryptedBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey())
        return cipher.doFinal(encryptedBytes)
    }
    private suspend fun handleFileUpload(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val contentResolver = applicationContext.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                
                // 1. Get file name
                var fileName = "file_${System.currentTimeMillis()}"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                // 2. Check file size (5MB limit as requested)
                val fileSize = contentResolver.openInputStream(uri)?.available() ?: 0
                if (fileSize > 5 * 1024 * 1024) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "File too large (max 5MB)", Toast.LENGTH_LONG).show()
                    }
                    return@withContext
                }

                // 3. Read file and compress to WebP if it's an image
                var fileBytes = contentResolver.openInputStream(uri)?.readBytes() ?: return@withContext
                var finalMimeType = mimeType
                var finalFileName = fileName

                if (mimeType.startsWith("image/")) {
                    try {
                        val source = ImageDecoder.createSource(contentResolver, uri)
                        val bitmap = ImageDecoder.decodeBitmap(source)
                        val baos = ByteArrayOutputStream()
                        
                        // Use WEBP format
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

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Encrypting and Uploading $finalFileName...", Toast.LENGTH_SHORT).show()
                }

                // 4. Encrypt the file bytes and Base64 encode for JSON payload
                val encryptedBytes = encryptFileBytes(fileBytes)
                val base64File = Base64.encodeToString(encryptedBytes, Base64.DEFAULT)

                // 5. Build JSON payload for Google Apps Script
                val jsonPayload = JSONObject().apply {
                    put("fileName", finalFileName)
                    put("mimeType", "application/octet-stream") // It's encrypted binary now
                    put("fileBase64", base64File)
                }

                val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

                val request = Request.Builder()
                    .url(BuildConfig.GAS_UPLOAD_URL)
                    .post(requestBody)
                    .build()

                // 6. Send to Google Apps Script
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
                            
                            // Send message with the new file ID
                            sendMessage(text, fileId, finalMimeType, finalFileName)
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "GAS Error: ${jsonResponse.optString("message")}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Upload failed (${response.code})", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Upload error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun triggerDownload(fileId: String, fileName: String, fileType: String) {
    val url = "https://drive.google.com/uc?export=download&id=$fileId"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse(url)
    }
    startActivity(intent)
    Toast.makeText(this, "Opening $fileName...", Toast.LENGTH_SHORT).show()
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
        userCountText.text = "${chatHistory.map { it.device }.distinct().size} users"
    }

        private fun updateChatUI() {
        chatMessageContainer.removeAllViews()
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        for ((index, msg) in chatHistory.withIndex()) {
            val isMe = msg.device == currentDeviceName
            
            val bubbleShape = GradientDrawable()
            bubbleShape.cornerRadius = 48f
            if (isMe) {
                bubbleShape.setColor(Color.parseColor("#DCF8C6"))
            } else {
                bubbleShape.setColor(Color.parseColor("#FFFFFF"))
            }

            val bubbleLayout = LinearLayout(this)
            bubbleLayout.orientation = LinearLayout.VERTICAL
            bubbleLayout.background = bubbleShape
            bubbleLayout.setPadding(40, 32, 40, 32)
            bubbleLayout.elevation = 4f

            if (!isMe) {
                val deviceText = TextView(this)
                deviceText.text = msg.device
                deviceText.textSize = 12f
                deviceText.setTextColor(Color.parseColor("#007BFF"))
                deviceText.setTypeface(null, Typeface.BOLD)
                deviceText.setPadding(0, 0, 0, 8)
                bubbleLayout.addView(deviceText)
            }

                        if (msg.driveFileId != null && msg.fileType != null) {
    val decryptedFileId = decryptMessage(msg.driveFileId)
    val fileName = msg.fileName ?: "attachment"
    
    val attachmentContainer = LinearLayout(this)
    attachmentContainer.orientation = LinearLayout.HORIZONTAL
    attachmentContainer.gravity = Gravity.CENTER_VERTICAL
    attachmentContainer.setPadding(0, 8, 0, 16)

    val iconRes = if (msg.fileType.startsWith("image/")) 
        android.R.drawable.ic_menu_gallery 
    else 
        android.R.drawable.ic_menu_save
        
    val downloadIcon = ImageView(this)
    downloadIcon.setImageResource(iconRes)
    downloadIcon.setColorFilter(Color.parseColor("#075E54"))
    val iconParams = LinearLayout.LayoutParams(48, 48)
    iconParams.setMargins(0, 0, 16, 0)
    downloadIcon.layoutParams = iconParams

    val attachmentText = TextView(this)
    attachmentText.text = if (msg.fileType.startsWith("image/")) 
        "📷 $fileName" 
    else 
        "📎 $fileName"
    attachmentText.textSize = 14f
    attachmentText.setTextColor(Color.parseColor("#075E54"))

    attachmentContainer.addView(downloadIcon)
    attachmentContainer.addView(attachmentText)
    
    attachmentContainer.setOnClickListener {
        triggerDownload(decryptedFileId, fileName, msg.fileType)
    }
    
    bubbleLayout.addView(attachmentContainer)
}

            val decryptedText = decryptMessage(msg.message)

            val messageView = TextView(this)
            messageView.textSize = 16f
            messageView.setTextColor(Color.BLACK)

            if (currentSearchQuery.isNotEmpty() && decryptedText.contains(currentSearchQuery, ignoreCase = true)) {
                val spannable = SpannableString(decryptedText)
                val startPos = decryptedText.indexOf(currentSearchQuery, ignoreCase = true)
                val isFocusedMatch = searchMatchIndices.isNotEmpty() && currentSearchIndex >= 0 && searchMatchIndices[currentSearchIndex] == index
                
                val highlightColor = if (isFocusedMatch) Color.parseColor("#FF9800") else Color.YELLOW
                val textColor = if (isFocusedMatch) Color.WHITE else Color.BLACK

                spannable.setSpan(BackgroundColorSpan(highlightColor), startPos, startPos + currentSearchQuery.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(textColor), startPos, startPos + currentSearchQuery.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                messageView.text = spannable
            } else {
                messageView.text = decryptedText
            }

            bubbleLayout.addView(messageView)

            val timeText = TextView(this)
            timeText.text = timeFormat.format(Date(msg.timestamp))
            timeText.textSize = 10f
            timeText.setTextColor(Color.GRAY)
            timeText.setTypeface(null, Typeface.ITALIC)
            timeText.gravity = Gravity.END
            timeText.setPadding(0, 12, 0, 0)
            bubbleLayout.addView(timeText)

            val wrapper = LinearLayout(this)
            val wrapperParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            wrapperParams.setMargins(0, 12, 0, 12)
            wrapper.layoutParams = wrapperParams
            
            if (isMe) {
                wrapper.gravity = Gravity.END
                wrapper.setPadding(150, 0, 0, 0)
            } else {
                wrapper.gravity = Gravity.START
                wrapper.setPadding(0, 0, 150, 0)
            }
            
            wrapper.addView(bubbleLayout)
            chatMessageContainer.addView(wrapper)
        }

        if (currentSearchQuery.isEmpty() && chatMessageContainer.childCount > 0) {
            chatScrollView.post { chatScrollView.smoothScrollTo(0, chatMessageContainer.bottom) }
        }
    }
}
