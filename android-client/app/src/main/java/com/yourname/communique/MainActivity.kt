package com.yourname.communique

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class ChatMessage(val device: String, val message: String, val timestamp: Long)

class MainActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private var currentDeviceName = ""
    private var chatHistory = mutableListOf<ChatMessage>()
    private var isPolling = false
    private var isFirstLoad = true
    private val CHANNEL_ID = "communique_chat"
    
    // --- Search State Variables ---
    private var currentSearchQuery = ""

    private lateinit var chatMessageContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var userCountText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request Notification Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        createNotificationChannel()

        val manufacturer = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        currentDeviceName = "$manufacturer ${Build.MODEL}"

        // --- View Bindings ---
        val loginLayout = findViewById<LinearLayout>(R.id.loginLayout)
        val chatLayout = findViewById<RelativeLayout>(R.id.chatLayout)
        val loginTriggerButton = findViewById<Button>(R.id.loginTriggerButton)
        val pinContainer = findViewById<LinearLayout>(R.id.pinContainer)
        val pinInput = findViewById<EditText>(R.id.pinInput)
        val unlockButton = findViewById<Button>(R.id.unlockButton)
        val messageInput = findViewById<EditText>(R.id.messageInput)
        val detectedDeviceText = findViewById<TextView>(R.id.detectedDeviceText)
        val gifImageView = findViewById<ImageView>(R.id.loginGif)
        
        // Search Bindings
        userCountText = findViewById(R.id.userCountText)
        val searchIcon = findViewById<ImageView>(R.id.searchIcon)
        val searchContainer = findViewById<LinearLayout>(R.id.searchContainer)
        val searchInput = findViewById<EditText>(R.id.searchInput)
        val closeSearchBtn = findViewById<ImageButton>(R.id.closeSearchBtn)
        
        chatMessageContainer = findViewById(R.id.chatMessageContainer)
        chatScrollView = findViewById(R.id.chatScrollView)

        detectedDeviceText.text = "Device Name: $currentDeviceName"

        try { Glide.with(this).asGif().load(R.drawable.login).into(gifImageView) } catch (e: Exception) {}

        // --- Login Animations ---
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

        // --- Search Interactions ---
        searchIcon.setOnClickListener {
            if (searchContainer.visibility == View.VISIBLE) {
                searchContainer.visibility = View.GONE
                currentSearchQuery = ""
                searchInput.text.clear()
                updateChatUI() // Redraw to clear highlights
            } else {
                searchContainer.visibility = View.VISIBLE
                searchInput.requestFocus()
            }
        }
        
        closeSearchBtn.setOnClickListener {
            searchContainer.visibility = View.GONE
            currentSearchQuery = ""
            searchInput.text.clear()
            updateChatUI() // Redraw to clear highlights
        }
        
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s.toString()
                applySearchHighlight()
            }
        })

        findViewById<View>(R.id.sendButton).setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                messageInput.text.clear()
                val encryptedText = encryptMessage(text)
                val newMessage = ChatMessage(currentDeviceName, encryptedText, System.currentTimeMillis())
                
                chatHistory.add(newMessage)
                updateChatUI()
                CoroutineScope(Dispatchers.IO).launch { pushGistUpdate(chatHistory) }
            }
        }
    }

    // --- Search Logic ---
    private fun applySearchHighlight() {
        if (currentSearchQuery.isEmpty()) {
            updateChatUI() 
            return
        }

        var firstMatchFound = false

        for ((index, msg) in chatHistory.withIndex()) {
            val decryptedText = decryptMessage(msg.message)
            if (decryptedText.contains(currentSearchQuery, ignoreCase = true)) {
                
                // Navigate our layout hierarchy to find the TextView containing the message
                val wrapperLayout = chatMessageContainer.getChildAt(index) as? LinearLayout
                val bubbleLayout = wrapperLayout?.getChildAt(0) as? LinearLayout
                
                for (i in 0 until (bubbleLayout?.childCount ?: 0)) {
                   val view = bubbleLayout?.getChildAt(i)
                   if(view is TextView && view.text.toString() == decryptedText) {
                       val spannable = SpannableString(decryptedText)
                       val startPos = decryptedText.indexOf(currentSearchQuery, ignoreCase = true)
                       
                       // Apply yellow highlight
                       spannable.setSpan(
                           BackgroundColorSpan(Color.YELLOW),
                           startPos,
                           startPos + currentSearchQuery.length,
                           Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                       )
                       view.text = spannable
                       
                       // Automatically scroll to the first match
                       if(!firstMatchFound) {
                           chatScrollView.post {
                               chatScrollView.smoothScrollTo(0, wrapperLayout.top)
                           }
                           firstMatchFound = true
                       }
                   }
                }
            }
        }
    }

    // --- ENCRYPTION ---
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
        } catch (e: Exception) { "🔒 [Decryption Failed]" }
    }

    // --- NOTIFICATIONS & NETWORK ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Communique Chat", NotificationManager.IMPORTANCE_HIGH)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
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
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(applicationContext, notification).play()
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
                            updateUserCount() // Refresh the user count
                            
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
                put("chat_ledger.json", JSONObject().apply { put("content", gson.toJson(history)) })
            })
        }
        val request = Request.Builder()
            .url("https://api.github.com/gists/${BuildConfig.CHAT_GIST_ID}")
            .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
            .patch(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try { httpClient.newCall(request).execute().close() } catch (e: Exception) {}
    }

    // --- UI HELPERS ---
    private fun updateUserCount() {
        val uniqueDevices = chatHistory.map { it.device }.distinct().size
        userCountText.text = "$uniqueDevices users"
    }

    private fun updateChatUI() {
        chatMessageContainer.removeAllViews()
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        for (msg in chatHistory) {
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

            bubbleLayout.addView(TextView(this).apply {
                text = decryptMessage(msg.message)
                textSize = 16f
                setTextColor(Color.BLACK)
            })

            bubbleLayout.addView(TextView(this).apply {
                text = timeFormat.format(Date(msg.timestamp))
                textSize = 10f
                setTextColor(Color.GRAY)
                setTypeface(null, Typeface.ITALIC)
                gravity = Gravity.END
                setPadding(0, 12, 0, 0)
            })

            val wrapper = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 12, 0, 12)
                }
                gravity = if (isMe) Gravity.END else Gravity.START
                setPadding(if (isMe) 150 else 0, 0, if (isMe) 0 else 150, 0)
            }
            wrapper.addView(bubbleLayout)
            chatMessageContainer.addView(wrapper)
        }
        
        if(currentSearchQuery.isEmpty()) {
            chatScrollView.post { chatScrollView.smoothScrollTo(0, chatMessageContainer.bottom) }
        } else {
            applySearchHighlight()
        }
    }
}
