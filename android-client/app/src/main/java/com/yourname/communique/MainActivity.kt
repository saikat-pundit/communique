package com.yourname.communique

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

data class ChatMessage(val device: String, val message: String, val timestamp: Long, val driveFileId: String? = null, val fileType: String? = null)

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
    private var googleDriveAccessToken: String? = null
    private val TARGET_DRIVE_FOLDER_ID = "1RjvnpZ6Nhwf22-7lrTvQxU_0wn-HM_1e"

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
                    CoroutineScope(Dispatchers.IO).launch { fetchDriveAccessToken() }
                }.start()
            } else {
                Toast.makeText(this, "Incorrect App Lock PIN", Toast.LENGTH_SHORT).show()
            }
        }

        searchIcon.setOnClickListener {
            if (searchContainer.visibility == View.VISIBLE) closeSearch(searchContainer, searchInput)
            else { searchContainer.visibility = View.VISIBLE; searchInput.requestFocus() }
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

    private fun sendMessage(rawText: String, driveFileId: String?, fileType: String?) {
        val encryptedText = encryptMessage(rawText)
        val encryptedFileId = driveFileId?.let { encryptMessage(it) }
        
        val newMessage = ChatMessage(currentDeviceName, encryptedText, System.currentTimeMillis(), encryptedFileId, fileType)
        chatHistory.add(newMessage)
        CoroutineScope(Dispatchers.Main).launch { updateChatUI() }
        CoroutineScope(Dispatchers.IO).launch { pushGistUpdate(chatHistory) }
    }

    private suspend fun fetchDriveAccessToken() {
        try {
            val b64Json = BuildConfig.DRIVE_JSON_B64
            if (b64Json.isEmpty()) return
            
            val jsonString = String(Base64.decode(b64Json, Base64.DEFAULT), Charsets.UTF_8)
            val serviceAccount = JSONObject(jsonString)
            
            val clientEmail = serviceAccount.getString("client_email")
            val privateKeyString = serviceAccount.getString("private_key")
                .replace("-----BEGIN PRIVATE KEY-----\n", "")
                .replace("-----END PRIVATE KEY-----\n", "")
                .replace("\n", "")

            val header = JSONObject().apply {
                put("alg", "RS256")
                put("typ", "JWT")
            }.toString().let { Base64.encodeToString(it.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

            val now = System.currentTimeMillis() / 1000
            val claimSet = JSONObject().apply {
                put("iss", clientEmail)
                put("scope", "https://www.googleapis.com/auth/drive.file")
                put("aud", "https://oauth2.googleapis.com/token")
                put("exp", now + 3600)
                put("iat", now)
            }.toString().let { Base64.encodeToString(it.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

            val unsignedJwt = "$header.$claimSet"

            val keyBytes = Base64.decode(privateKeyString, Base64.DEFAULT)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val kf = KeyFactory.getInstance("RSA")
            val privateKey = kf.generatePrivate(keySpec)

            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(unsignedJwt.toByteArray(Charsets.UTF_8))
            val signedBytes = signature.sign()
            
            val signatureBase64 = Base64.encodeToString(signedBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val jwt = "$unsignedJwt.$signatureBase64"

            val formBody = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("assertion", jwt)
                .build()

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(formBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respString = response.body?.string()
                    if (respString != null) {
                        googleDriveAccessToken = JSONObject(respString).getString("access_token")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun handleFileUpload(uri: Uri) {
        if (googleDriveAccessToken == null) {
            CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Drive Auth Failed. Cannot upload.", Toast.LENGTH_SHORT).show() }
            return
        }

        val contentResolver = applicationContext.contentResolver
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        
        var fileName = "attachment"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) fileName = cursor.getString(nameIndex)
        }

        var fileBytes = contentResolver.openInputStream(uri)?.readBytes() ?: return
        var finalMimeType = mimeType

        if (mimeType.startsWith("image/")) {
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source)
                val baos = ByteArrayOutputStream()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, baos)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 80, baos)
                }
                fileBytes = baos.toByteArray()
                finalMimeType = "image/webp"
                fileName = "${fileName.substringBeforeLast(".")}.webp"
            } catch (e: Exception) { e.printStackTrace() }
        }

        CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Uploading $fileName...", Toast.LENGTH_SHORT).show() }
        
        val metadata = JSONObject().apply {
            put("name", fileName)
            put("parents", org.json.JSONArray().put(TARGET_DRIVE_FOLDER_ID))
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", null, metadata.toString().toRequestBody("application/json".toMediaType()))
            .addFormDataPart("file", fileName, fileBytes.toRequestBody(finalMimeType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .addHeader("Authorization", "Bearer $googleDriveAccessToken")
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respString = response.body?.string()
                    if (respString != null) {
                        val fileId = JSONObject(respString).getString("id")
                        val uiText = messageInput.text.toString().ifEmpty { "Sent an attachment" }
                        CoroutineScope(Dispatchers.Main).launch { messageInput.text.clear() }
                        sendMessage(uiText, fileId, finalMimeType)
                    }
                } else {
                    CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Upload Failed", Toast.LENGTH_SHORT).show() }
                }
            }
        } catch (e: Exception) {
            CoroutineScope(Dispatchers.Main).launch { Toast.makeText(this@MainActivity, "Network Error on Upload", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun triggerDownload(fileId: String, fileName: String) {
        val url = "https://drive.google.com/uc?export=download&id=$fileId"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
        }
        startActivity(intent)
        Toast.makeText(this, "Opening attachment...", Toast.LENGTH_SHORT).show()
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
        } else searchIndicatorLayout.visibility = View.GONE
        
        updateSearchIndicatorAndScroll()
    }

    private fun updateSearchIndicatorAndScroll() {
        if (searchMatchIndices.isEmpty()) return
        searchPositionText.text = "(${currentSearchIndex + 1}/${searchMatchIndices.size})"
        updateChatUI()
        val targetGlobalIndex = searchMatchIndices[currentSearchIndex]
        val wrapperLayout = chatMessageContainer.getChildAt(targetGlobalIndex)
        if (wrapperLayout != null) chatScrollView.post { chatScrollView.smoothScrollTo(0, wrapperLayout.top) }
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
        } catch (e: Exception) { "ðŸ”’ [Decryption Failed]" }
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
        } else notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun playNotificationSound() {
        try { RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (e: Exception) {}
    }

    private fun startPollingGist() {
        CoroutineScope(Dispatchers.IO).launch { while (isPolling) { fetchGist(); delay(5000) } }
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
            put("files", JSONObject().apply { put("chat_ledger.json", JSONObject().apply { put("content", gson.toJson(history)) }) })
        }
        val request = Request.Builder()
            .url("https://api.github.com/gists/${BuildConfig.CHAT_GIST_ID}")
            .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
            .patch(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try { httpClient.newCall(request).execute().close() } catch (e: Exception) {}
    }

    private fun updateUserCount() {
        userCountText.text = "${chatHistory.map { it.device }.distinct().size} users"
    }

        private fun updateChatUI() {
        chatMessageContainer.removeAllViews()
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        for ((index, msg) in chatHistory.withIndex()) {
            val isMe = msg.device == currentDeviceName
            val bubbleShape = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(if (isMe) Color.parseColor("#DCF8C6") else Color.parseColor("#FFFFFF"))
            }

            val bubbleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = bubbleShape
                setPadding(40, 32, 40, 32)
                elevation = 4f
            }

            if (!isMe) {
                bubbleLayout.addView(TextView(this).apply {
                    text = msg.device
                    textSize = 12f
                    setTextColor(Color.parseColor("#007BFF"))
                    setTypeface(null, Typeface.BOLD)
                    setPadding(0, 0, 0, 8)
                })
            }

            if (msg.driveFileId != null && msg.fileType != null) {
                val decryptedFileId = decryptMessage(msg.driveFileId)
                
                val attachmentContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 16)
                }

                val downloadIcon = ImageView(this).apply {
                    setImageResource(android.R.drawable.stat_sys_download)
                    setColorFilter(Color.parseColor("#075E54"))
                    layoutParams = LinearLayout.LayoutParams(64, 64).apply { setMargins(0, 0, 16, 0) }
                }

                val attachmentText = TextView(this).apply {
                    text = if (msg.fileType.startsWith("image/")) "Image Attachment" else "Document Attachment"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#075E54"))
                }

                attachmentContainer.addView(downloadIcon)
                attachmentContainer.addView(attachmentText)
                
                attachmentContainer.setOnClickListener {
                    triggerDownload(decryptedFileId, "attachment")
                }
                
                bubbleLayout.addView(attachmentContainer)
            }

            val decryptedText = decryptMessage(msg.message)

            val messageView = TextView(this).apply {
                textSize = 16f
                setTextColor(Color.BLACK)
            }

            // Fixed if expression
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

            bubbleLayout.addView(TextView(this).apply {
                text = timeFormat.format(Date(msg.timestamp))
                textSize = 10f
                setTextColor(Color.GRAY)
                setTypeface(null, Typeface.ITALIC)
                gravity = Gravity.END
                setPadding(0, 12, 0, 0)
            })

            val wrapper = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 12, 0, 12) }
                gravity = if (isMe) Gravity.END else Gravity.START
                setPadding(if (isMe) 150 else 0, 0, if (isMe) 0 else 150, 0)
            }
            wrapper.addView(bubbleLayout)
            chatMessageContainer.addView(wrapper)
        }

        if (currentSearchQuery.isEmpty() && chatMessageContainer.childCount > 0) {
            chatScrollView.post { chatScrollView.smoothScrollTo(0, chatMessageContainer.bottom) }
        }
    }
}
