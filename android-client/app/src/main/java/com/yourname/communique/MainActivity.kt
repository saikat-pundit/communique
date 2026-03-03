package com.yourname.communique

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var selectedFileUri: Uri? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            findViewById<TextView>(R.id.selectedFileText).text = "File selected and ready to upload"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val messageInput = findViewById<EditText>(R.id.messageInput)
        val pickFileButton = findViewById<Button>(R.id.pickFileButton)
        val sendButton = findViewById<Button>(R.id.sendButton)

        pickFileButton.setOnClickListener {
            filePickerLauncher.launch("*/*") 
        }

        sendButton.setOnClickListener {
            val textToSend = messageInput.text.toString()
            println("Ready to send: $textToSend with file: $selectedFileUri")
            // We will inject the API logic here in the next step
        }
    }
}
