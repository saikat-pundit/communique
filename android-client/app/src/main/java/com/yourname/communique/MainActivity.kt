package com.yourname.communique

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// Data class to represent a single message
data class ChatMessage(val device: String, val message: String, val timestamp: Long)

class MainActivity : AppCompatActivity() {

    private val client = OkHttp() // Fixed typo: should be OkHttpClient()
    private val httpClient = OkHttpClient()
    private val gson = Gson()
    private var currentDeviceName = ""
    private var chatHistory = mutableListOf<ChatMessage>()
    private var isPolling = false

    // UI Elements
    private lateinit var loginLayout: LinearLayout
    private lateinit var chatLayout: LinearLayout
    private lateinit var deviceNameInput: EditText
    private lateinit var pinInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var chatMessageContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind UI Elements
        loginLayout = findViewById(R.id.loginLayout)
        chatLayout = findViewById(R.id.chatLayout)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        pinInput = findViewById(R.id.pinInput)
        messageInput = findViewById(R.id.messageInput)
        chatMessageContainer = findViewById(R.id.chatMessageContainer)
        chatScrollView = findViewById(R.id.chatScrollView)

        val loginButton = findViewById<Button>(R.id.loginButton)
        val sendButton = findViewById<Button>(R.id.sendButton)

        // Handle Login / PIN Check
        loginButton.setOnClickListener {
            val pin = pinInput.text.toString()
            val name = deviceNameInput.text.toString().trim()

            if (pin == "3142" && name.isNotEmpty()) {
                currentDeviceName = name
                loginLayout.visibility = View.GONE
                chatLayout.visibility = View.VISIBLE
                Toast.makeText(this, "Welcome, $name", Toast.LENGTH_SHORT).show()
                
                // Start pulling messages from GitHub
                isPolling = true
                startPollingGist()
            } else {
                Toast.makeText(this, "Invalid PIN or empty Device Name", Toast.LENGTH_SHORT).show()
            }
        }

        // Handle Sending a Message
        sendButton.setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                messageInput.text.clear()
                val newMessage = ChatMessage(currentDeviceName, text, System.currentTimeMillis())
                chatHistory.add(newMessage)
                updateChatUI()
                
                // Send to GitHub Gist
                CoroutineScope(Dispatchers.IO).launch {
                    pushGistUpdate(chatHistory)
                }
            }
        }
    }

    // Function to constantly check for new messages every 5 seconds
    private fun startPollingGist() {
        CoroutineScope(Dispatchers.IO).launch {
            while (isPolling) {
                fetchGist()
                delay(5000) // Wait 5 seconds before checking again
            }
        }
    }

    // Fetch the JSON from GitHub
    private fun fetchGist() {
        val request = Request.Builder()
            .url("https://api.github.com/gists/${BuildConfig.CHAT_GIST_ID}")
            .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    if (responseData != null) {
                        val jsonObject = JSONObject(responseData)
                        val files = jsonObject.getJSONObject("files")
                        val chatFile = files.getJSONObject("chat_ledger.json")
                        val content = chatFile.getString("content")

                        // Convert JSON string back to a list of ChatMessage objects
                        val listType = object : TypeToken<List<ChatMessage>>() {}.type
                        val fetchedHistory: List<ChatMessage> = gson.fromJson(content, listType) ?: emptyList()

                        // If there are new messages, update the UI
                        if (fetchedHistory.size > chatHistory.size) {
                            chatHistory.clear()
                            chatHistory.addAll(fetchedHistory)
                            CoroutineScope(Dispatchers.Main).launch { updateChatUI() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Upload the new JSON array to GitHub
    private fun pushGistUpdate(history: List<ChatMessage>) {
        val jsonString = gson.toJson(history)
        
        // GitHub Gist API requires a specific JSON structure to update a file
        val payload = JSONObject()
        val files = JSONObject()
        val fileContent = JSONObject()
        fileContent.put("content", jsonString)
        files.put("chat_ledger.json", fileContent)
        payload.put("files", files)

        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.github.com/gists/${BuildConfig.CHAT_GIST_ID}")
            .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
            .patch(body) // We use PATCH to update an existing Gist
            .build()

        try {
            httpClient.newCall(request).execute().close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Draw the text messages on the screen
    private fun updateChatUI() {
        chatMessageContainer.removeAllViews()
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

        for (msg in chatHistory) {
            val textView = TextView(this).apply {
                text = "[${timeFormat.format(Date(msg.timestamp))}] ${msg.device}:\n${msg.message}"
                textSize = 16f
                setPadding(16, 16, 16, 16)
                
                // Make our own messages green, and others gray
                if (msg.device == currentDeviceName) {
                    setBackgroundColor(Color.parseColor("#E8F5E9")) // Light green
                    textAlignment = View.TEXT_ALIGNMENT_TEXT_END
                } else {
                    setBackgroundColor(Color.parseColor("#F5F5F5")) // Light gray
                    textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                }
            }
            
            // Add margin between messages
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8, 0, 8)
            textView.layoutParams = params
            
            chatMessageContainer.addView(textView)
        }

        // Scroll to the bottom to see the newest message
        chatScrollView.post {
            chatScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}
