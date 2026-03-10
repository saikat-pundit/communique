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
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Okio
import okio.Sink
import okio.buffer
import android.view.inputmethod.InputMethodManager
import android.app.AlertDialog
import android.content.DialogInterface

// DATA MODEL
data class ChatMessage(
    val device: String, 
    val message: String, 
    val timestamp: Long, 
    val driveFileId: String? = null, 
    val fileType: String? = null, 
    val fileName: String? = null,
    val replyToDevice: String? = null,
    val replyToText: String? = null,
    val groupName: String? = null
)

class MainActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private val networkHelper = NetworkHelper(httpClient, gson)
    private var currentDeviceName = ""
    private var chatHistory = mutableListOf<ChatMessage>()
    private var isPolling = false
    private var isFirstLoad = true
    private val CHANNEL_ID = "communique_chat"
    private var currentSearchQuery = ""
    private var searchMatchIndices = mutableListOf<Int>()
    private var currentSearchIndex = -1
    private var replyingToDevice: String? = null
    private var replyingToText: String? = null
    
    private lateinit var chatMessageContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var userCountText: TextView
    private lateinit var searchPositionText: TextView
    private lateinit var searchIndicatorLayout: LinearLayout
    private lateinit var messageInput: EditText
    private var currentGroupName: String? = null
    private lateinit var groupOverlay: FrameLayout
    private lateinit var mediaManager: MediaManager
    private lateinit var chatLayout: RelativeLayout
    private lateinit var sharedPrefs: android.content.SharedPreferences

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
        sharedPrefs = getSharedPreferences("CommuniqueCache", Context.MODE_PRIVATE)
        
        // Load Cache
        val cachedJson = sharedPrefs.getString("chat_ledger_cache", null)
        if (cachedJson != null) {
            try {
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                val cachedList: List<ChatMessage> = gson.fromJson(cachedJson, type)
                chatHistory.clear()
                chatHistory.addAll(cachedList)
            } catch (e: Exception) { e.printStackTrace() }
        }

        mediaManager = MediaManager(this, httpClient)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        createNotificationChannel()

        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        currentDeviceName = "$manufacturer ${Build.MODEL}"

        // UI Initialization
        val loginLayout = findViewById<LinearLayout>(R.id.loginLayout)
        chatLayout = findViewById(R.id.chatLayout)
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

        groupOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            visibility = View.GONE
            elevation = 100f
            setBackgroundColor(Color.WHITE)
        }
        addContentView(groupOverlay, groupOverlay.layoutParams)

        val backButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            layoutParams = LinearLayout.LayoutParams(90, 90).apply { setMargins(0, 0, 32, 0) }
            setPadding(12, 12, 12, 12)
            setOnClickListener {
                currentGroupName?.let { markGroupAsRead(it) }
                currentGroupName = null
                chatLayout.visibility = View.GONE
                showGroupScreen()
            }
        }
        (userCountText.parent as LinearLayout).addView(backButton, 0)

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
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(pinInput.windowToken, 0)

                loginLayout.animate().alpha(0f).setDuration(500).withEndAction {
                    loginLayout.visibility = View.GONE
                    chatLayout.visibility = View.VISIBLE
                    chatLayout.alpha = 1f
                    isPolling = true
                    startPollingGist()
                    if (!isFinishing) showGroupScreen()
                }.start()
            } else {
                Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            }
        }

        // Search Listeners
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
        searchUpBtn.setOnClickListener { navigateSearch(-1) }
        searchDownBtn.setOnClickListener { navigateSearch(1) }

        attachButton.setOnClickListener { filePickerLauncher.launch("*/*") }
        findViewById<View>(R.id.sendButton).setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                messageInput.text.clear()
                sendMessage(text)
            }
        }
    }

    private fun navigateSearch(direction: Int) {
        if (searchMatchIndices.isNotEmpty()) {
            currentSearchIndex = if (direction > 0) {
                if (currentSearchIndex < searchMatchIndices.size - 1) currentSearchIndex + 1 else 0
            } else {
                if (currentSearchIndex > 0) currentSearchIndex - 1 else searchMatchIndices.size - 1
            }
            updateSearchIndicatorAndScroll()
        }
    }

    private fun saveCacheAndReadState() {
        sharedPrefs.edit().putString("chat_ledger_cache", gson.toJson(chatHistory)).apply()
        currentGroupName?.let { markGroupAsRead(it) }
    }

    private fun markGroupAsRead(group: String) {
        val count = chatHistory.count { (it.groupName ?: "Personal Chat") == group }
        sharedPrefs.edit().putInt("read_count_$group", count).apply()
    }

    private fun getUnreadCounts(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val grouped = chatHistory.groupBy { it.groupName ?: "Personal Chat" }
        for ((group, messages) in grouped) {
            val lastRead = sharedPrefs.getInt("read_count_$group", 0)
            val unread = messages.size - lastRead
            if (unread > 0) counts[group] = unread
        }
        return counts
    }

    private fun showGroupScreen() {
        groupOverlay.removeAllViews()
        val screen = GroupUIHelper.buildGroupScreen(
            context = this,
            chatHistory = chatHistory,
            unreadCounts = getUnreadCounts(),
            onGroupSelected = { groupName ->
                currentGroupName = groupName
                markGroupAsRead(groupName)
                groupOverlay.visibility = View.GONE
                chatLayout.visibility = View.VISIBLE
                updateChatUI()
                updateUserCount()
            },
            onGroupCreated = { newGroupName ->
                currentGroupName = newGroupName
                markGroupAsRead(newGroupName)
                groupOverlay.visibility = View.GONE
                chatLayout.visibility = View.VISIBLE
                sendMessage("Created group: $newGroupName")
            },
            onGroupRename = { oldName, newName ->
                val updatedHistory = chatHistory.map {
                    if ((it.groupName ?: "Personal Chat") == oldName) it.copy(groupName = newName) else it
                }
                chatHistory.clear()
                chatHistory.addAll(updatedHistory)
                val sysMsg = ChatMessage(currentDeviceName, CryptoHelper.encrypt("🔄 Group renamed to '$newName'"), System.currentTimeMillis(), groupName = newName)
                chatHistory.add(sysMsg)
                saveCacheAndReadState()
                CoroutineScope(Dispatchers.IO).launch { networkHelper.pushGistUpdate(chatHistory) }
                showGroupScreen()
            },
            onGroupDelete = { groupToDelete ->
                // Pin confirmation and deletion logic...
                confirmDeletion(groupToDelete)
            }
        )
        groupOverlay.addView(screen)
        groupOverlay.visibility = View.VISIBLE
    }

    private fun confirmDeletion(groupToDelete: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Group?")
            .setMessage("Permanently delete '$groupToDelete'?")
            .setPositiveButton("Yes") { _, _ ->
                val pinIn = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
                AlertDialog.Builder(this).setTitle("Confirm PIN").setView(pinIn).setPositiveButton("Confirm") { _, _ ->
                    if (pinIn.text.toString() == "3142") {
                        chatHistory.removeAll { (it.groupName ?: "Personal Chat") == groupToDelete }
                        sharedPrefs.edit().remove("read_count_$groupToDelete").apply()
                        saveCacheAndReadState()
                        CoroutineScope(Dispatchers.IO).launch { networkHelper.pushGistUpdate(chatHistory) }
                        showGroupScreen()
                    }
                }.show()
            }.setNegativeButton("No", null).show()
    }

    private fun sendMessage(rawText: String, driveFileId: String? = null, fileType: String? = null, fileName: String? = null) {
        val encryptedText = CryptoHelper.encrypt(rawText)
        val encryptedFileId = driveFileId?.let { CryptoHelper.encrypt(it) }
        val encReplyDev = replyingToDevice?.let { CryptoHelper.encrypt(it) }
        val encReplyTxt = replyingToText?.let { CryptoHelper.encrypt(it) }

        val newMessage = ChatMessage(currentDeviceName, encryptedText, System.currentTimeMillis(), encryptedFileId, fileType, fileName, encReplyDev, encReplyTxt, currentGroupName ?: "Personal Chat")
        
        replyingToDevice = null
        replyingToText = null
        messageInput.hint = "Type a message..."

        CoroutineScope(Dispatchers.IO).launch {
            val latest = networkHelper.fetchChatHistory()
            if (!latest.isNullOrEmpty()) {
                chatHistory.clear()
                chatHistory.addAll(latest)
            }
            chatHistory.add(newMessage)
            saveCacheAndReadState()
            networkHelper.pushGistUpdate(chatHistory)
            withContext(Dispatchers.Main) { updateChatUI() }
        }
    }

    private suspend fun handleFileUpload(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val cr = applicationContext.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                var fName = "file_${System.currentTimeMillis()}"
                cr.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst()) fName = c.getString(idx)
                }
                
                val bytes = cr.openInputStream(uri)?.readBytes() ?: return@withContext
                val encBytes = mediaManager.encryptFileBytes(bytes)
                val base64 = Base64.encodeToString(encBytes, Base64.DEFAULT)

                val payload = JSONObject().apply {
                    put("fileName", fName)
                    put("mimeType", "application/octet-stream")
                    put("fileBase64", base64)
                }

                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val progressBody = ProgressRequestBody(body) { p ->
                    CoroutineScope(Dispatchers.Main).launch { messageInput.hint = "Uploading: $p%" }
                }

                val url = SecretDecoder.decode(BuildConfig.GAS_UPLOAD_URL)
                val req = Request.Builder().url(url).post(progressBody).build()
                httpClient.newCall(req).execute().use { resp ->
                    val respStr = resp.body?.string()
                    if (resp.isSuccessful && respStr != null) {
                        val jResp = JSONObject(respStr)
                        if (jResp.optString("status") == "success") {
                            val fid = jResp.getString("fileId")
                            withContext(Dispatchers.Main) {
                                messageInput.hint = "Type a message..."
                                sendMessage("Sent a file", fid, mime, fName)
                            }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun triggerDownload(fileId: String, fileName: String, fileType: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val file = mediaManager.downloadAndDecryptFile(fileId, fileName)
            if (file != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "${packageName}.provider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, fileType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    try { startActivity(intent) } catch (e: Exception) { Toast.makeText(this@MainActivity, "No app found", Toast.LENGTH_SHORT).show() }
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
        if (currentSearchQuery.isEmpty()) {
            searchIndicatorLayout.visibility = View.GONE
            updateChatUI()
            return
        }
        chatHistory.forEachIndexed { index, msg ->
            if (CryptoHelper.decrypt(msg.message).contains(currentSearchQuery, true) || msg.fileName?.contains(currentSearchQuery, true) == true) {
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
        val target = searchMatchIndices[currentSearchIndex]
        chatMessageContainer.getChildAt(target)?.let { v ->
            chatScrollView.post { chatScrollView.smoothScrollTo(0, v.top) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Communique", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }
    }

    private fun showNotification(s: String, m: String) {
        val b = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_send).setContentTitle(s).setContentText(m).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), b.build())
    }

    private fun playNotificationSound() {
        try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (e: Exception) {}
    }

    private fun startPollingGist() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isPolling) {
                val fetched = networkHelper.fetchChatHistory()
                if (fetched != null) {
                    val changed = fetched.size != chatHistory.size || (fetched.lastOrNull()?.timestamp != chatHistory.lastOrNull()?.timestamp)
                    if (changed || isFirstLoad) {
                        val isNew = fetched.size > chatHistory.size
                        val last = fetched.lastOrNull()
                        val isMe = last?.device == currentDeviceName
                        chatHistory.clear()
                        chatHistory.addAll(fetched)
                        saveCacheAndReadState()
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed) {
                                if (currentGroupName == null) {
                                    if (groupOverlay.visibility == View.VISIBLE || isFirstLoad) showGroupScreen()
                                } else {
                                    val exists = chatHistory.any { (it.groupName ?: "Personal Chat") == currentGroupName } || currentGroupName == "Personal Chat"
                                    if (!exists) { currentGroupName = null; chatLayout.visibility = View.GONE; showGroupScreen() }
                                    else { updateChatUI(); updateUserCount() }
                                }
                                if (!isFirstLoad && !isMe && isNew && last != null) {
                                    playNotificationSound()
                                    showNotification(last.device, CryptoHelper.decrypt(last.message))
                                }
                            }
                        }
                        isFirstLoad = false
                    }
                }
                delay(5000)
            }
        }
    }

    private fun updateUserCount() {
        val g = currentGroupName ?: "Personal Chat"
        val hist = chatHistory.filter { (it.groupName ?: "Personal Chat") == g }
        ChatUIHelper.updateUserCountBar(this, userCountText, hist, currentDeviceName, g)
    }

    private fun updateChatUI() {
        chatMessageContainer.removeAllViews()
        val g = currentGroupName ?: "Personal Chat"
        val hist = chatHistory.filter { (it.groupName ?: "Personal Chat") == g }
        val imgIdx = hist.indices.filter { hist[it].fileType?.startsWith("image/") == true }
        val autoIdx = imgIdx.takeLast(2)
        
        for ((i, msg) in hist.withIndex()) {
            val dTxt = CryptoHelper.decrypt(msg.message)
            val dFid = msg.driveFileId?.let { CryptoHelper.decrypt(it) }
            val isMatch = searchMatchIndices.isNotEmpty() && currentSearchIndex >= 0 && searchMatchIndices[currentSearchIndex] == i
            
            val bubble = ChatUIHelper.buildMessageBubble(
                this, msg, currentDeviceName, i in autoIdx, dTxt, dFid, currentSearchQuery, isMatch, mediaManager,
                { fid, fn, ft -> triggerDownload(fid, fn, ft) },
                { qd, qt -> 
                    replyingToDevice = qd
                    replyingToText = qt
                    messageInput.hint = "Replying to $qd..."
                }
            )
            chatMessageContainer.addView(bubble)
        }
        if (currentSearchQuery.isEmpty()) chatScrollView.post { chatScrollView.smoothScrollTo(0, chatMessageContainer.bottom) }
    }
}

// PROGRESS REQUEST BODY CLASS
class ProgressRequestBody(private val rb: RequestBody, private val onProgress: (Int) -> Unit) : RequestBody() {
    override fun contentType() = rb.contentType()
    override fun contentLength() = rb.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val countingSink = object : ForwardingSink(sink) {
            var bytesWritten = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(((bytesWritten.toFloat() / contentLength().toFloat()) * 100).toInt())
            }
        }
        val bufferedSink = countingSink.buffer()
        rb.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}
