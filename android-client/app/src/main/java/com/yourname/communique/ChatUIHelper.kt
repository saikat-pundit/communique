package com.yourname.communique

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatUIHelper {

    // FEATURE 3: Top Bar User Names and Modal Popup
    fun updateUserCountBar(context: Context, userCountText: TextView, chatHistory: List<ChatMessage>) {
        val users = chatHistory.map { it.device }.distinct()
        val count = users.size
        
        if (count == 0) {
            userCountText.text = "0 users"
            return
        }

        // Show "3 users (Phone A, Phone B...)"
        val displayNames = users.take(2).joinToString(", ") + if (count > 2) "..." else ""
        userCountText.text = "$count users ($displayNames)"

        // Open Modal on Click
        userCountText.setOnClickListener {
            AlertDialog.Builder(context)
                .setTitle("Connected Users")
                .setItems(users.toTypedArray(), null)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    // FEATURE 2: Minimized Padding & FEATURE 1: Media Download Restriction
    fun buildMessageBubble(
        context: Context,
        msg: ChatMessage,
        currentDeviceName: String,
        isAutoDownload: Boolean,
        decryptedText: String,
        decryptedFileId: String?,
        currentSearchQuery: String,
        isFocusedSearchMatch: Boolean,
        mediaManager: MediaManager,
        onDownloadClicked: (String, String, String) -> Unit
    ): LinearLayout {
        val isMe = msg.device == currentDeviceName
        
        val bubbleShape = GradientDrawable()
        bubbleShape.cornerRadius = 32f // Slightly sharper corners save space
        if (isMe) bubbleShape.setColor(Color.parseColor("#DCF8C6")) else bubbleShape.setColor(Color.parseColor("#FFFFFF"))

        val bubbleLayout = LinearLayout(context)
        bubbleLayout.orientation = LinearLayout.VERTICAL
        bubbleLayout.background = bubbleShape
        bubbleLayout.setPadding(24, 16, 24, 16) // REDUCED: Minimal internal padding
        bubbleLayout.elevation = 2f

        if (!isMe) {
            val deviceText = TextView(context)
            deviceText.text = msg.device
            deviceText.textSize = 11f // Slightly smaller text for names
            deviceText.setTextColor(Color.parseColor("#007BFF"))
            deviceText.setTypeface(null, Typeface.BOLD)
            deviceText.setPadding(0, 0, 0, 4) // REDUCED PADDING
            bubbleLayout.addView(deviceText)
        }

        if (decryptedFileId != null && msg.fileType != null) {
            val fileName = msg.fileName ?: "attachment"
            
            // Only auto-download if it's an image AND the isAutoDownload flag is true
            if (msg.fileType.startsWith("image/") && isAutoDownload) {
                val thumbnailView = ImageView(context)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400) // Reduced height
                params.setMargins(0, 4, 0, 8)
                thumbnailView.layoutParams = params
                thumbnailView.scaleType = ImageView.ScaleType.CENTER_CROP
                thumbnailView.setBackgroundColor(Color.parseColor("#DDDDDD"))
                
                thumbnailView.setOnClickListener { onDownloadClicked(decryptedFileId, fileName, msg.fileType) }
                bubbleLayout.addView(thumbnailView)

                CoroutineScope(Dispatchers.IO).launch {
                    mediaManager.loadThumbnail(decryptedFileId, fileName, thumbnailView)
                }
            } else {
                // Click to view for EVERYTHING else
                val attachmentContainer = LinearLayout(context)
                attachmentContainer.orientation = LinearLayout.HORIZONTAL
                attachmentContainer.gravity = Gravity.CENTER_VERTICAL
                attachmentContainer.setPadding(0, 4, 0, 8)

                val downloadIcon = ImageView(context)
                downloadIcon.setImageResource(android.R.drawable.ic_menu_save)
                downloadIcon.setColorFilter(Color.parseColor("#075E54"))
                val iconParams = LinearLayout.LayoutParams(40, 40) // Smaller icon
                iconParams.setMargins(0, 0, 12, 0)
                downloadIcon.layoutParams = iconParams

                val attachmentText = TextView(context)
                attachmentText.text = if (msg.fileType.startsWith("image/")) "🖼️ Tap to view image" else "📎 $fileName"
                attachmentText.textSize = 13f
                attachmentText.setTextColor(Color.parseColor("#075E54"))

                attachmentContainer.addView(downloadIcon)
                attachmentContainer.addView(attachmentText)
                
                attachmentContainer.setOnClickListener { onDownloadClicked(decryptedFileId, fileName, msg.fileType) }
                bubbleLayout.addView(attachmentContainer)
            }
        }

        val messageView = TextView(context)
        messageView.textSize = 15f
        messageView.setTextColor(Color.BLACK)

        if (currentSearchQuery.isNotEmpty() && decryptedText.contains(currentSearchQuery, ignoreCase = true)) {
            val spannable = SpannableString(decryptedText)
            val startPos = decryptedText.indexOf(currentSearchQuery, ignoreCase = true)
            
            val highlightColor = if (isFocusedSearchMatch) Color.parseColor("#FF9800") else Color.YELLOW
            val textColor = if (isFocusedSearchMatch) Color.WHITE else Color.BLACK

            spannable.setSpan(BackgroundColorSpan(highlightColor), startPos, startPos + currentSearchQuery.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(textColor), startPos, startPos + currentSearchQuery.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            messageView.text = spannable
        } else {
            messageView.text = decryptedText
        }
        bubbleLayout.addView(messageView)

        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val timeText = TextView(context)
        timeText.text = timeFormat.format(Date(msg.timestamp))
        timeText.textSize = 9f
        timeText.setTextColor(Color.GRAY)
        timeText.setTypeface(null, Typeface.ITALIC)
        timeText.gravity = Gravity.END
        timeText.setPadding(0, 4, 0, 0) // REDUCED PADDING
        bubbleLayout.addView(timeText)

        val wrapper = LinearLayout(context)
        val wrapperParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        wrapperParams.setMargins(0, 6, 0, 6) // REDUCED: Space between message bubbles
        wrapper.layoutParams = wrapperParams
        
        if (isMe) {
            wrapper.gravity = Gravity.END
            wrapper.setPadding(100, 0, 0, 0) // Allows bubble to stretch wider
        } else {
            wrapper.gravity = Gravity.START
            wrapper.setPadding(0, 0, 100, 0)
        }
        
        wrapper.addView(bubbleLayout)
        return wrapper
    }
}
