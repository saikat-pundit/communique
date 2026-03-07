package com.yourname.communique

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*

object GroupUIHelper {
    fun buildGroupScreen(
        context: Context,
        chatHistory: List<ChatMessage>,
        unreadCounts: Map<String, Int>, // <--- ADD THIS LINE
        onGroupSelected: (String) -> Unit,
        onGroupCreated: (String) -> Unit
    ): LinearLayout {
        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#E5DDD5"))
            setPadding(40, 80, 40, 40)
        }

        // --- NEW: Header Row with Title & Search Icon ---
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, 40)
        }

        val title = TextView(context).apply {
            text = "💬 Your Groups"
            textSize = 28f
            setTextColor(Color.parseColor("#075E54"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val searchBtn = TextView(context).apply {
            text = "🔍"
            textSize = 24f
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D9D1C7"))
                cornerRadius = 50f
            }
        }

        headerRow.addView(title)
        headerRow.addView(searchBtn)
        mainLayout.addView(headerRow)

        // --- NEW: Hidden Search Bar Row ---
        val searchBarRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, 40)
            visibility = View.GONE
        }

        val searchInput = EditText(context).apply {
            hint = "Search groups..."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(40, 30, 40, 30)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24f
            }
            isSingleLine = true
        }

        val closeSearchBtn = TextView(context).apply {
            text = "✖"
            textSize = 20f
            setTextColor(Color.DKGRAY)
            setTypeface(null, Typeface.BOLD)
            setPadding(40, 20, 20, 20)
        }

        searchBarRow.addView(searchInput)
        searchBarRow.addView(closeSearchBtn)
        mainLayout.addView(searchBarRow)

        // Scrollable List Container
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val listLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Extract Groups
        val allGroups = chatHistory.map { it.groupName ?: "Personal Chat" }.distinct().toMutableList()
        if (!allGroups.contains("Personal Chat")) allGroups.add(0, "Personal Chat")

        // --- NEW: Dynamic Render Function ---
        fun renderList(query: String) {
            listLayout.removeAllViews()
            val filtered = allGroups.filter { it.contains(query, ignoreCase = true) }
            
            filtered.forEach { group ->
                val groupBtn = Button(context).apply {
                    text = "📁  $group"
                    textSize = 18f
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(50, 50, 50, 50)
                    isAllCaps = false
                    setTextColor(Color.BLACK)
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = 24f
                    }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { 
                        setMargins(0, 0, 0, 20) 
                    }
                    setOnClickListener { onGroupSelected(group) }
                }
                
                listLayout.addView(groupBtn)
            }
        }

        // Initial render
        renderList("")
        scrollView.addView(listLayout)
        mainLayout.addView(scrollView)

        // --- NEW: Search Interactions ---
        searchBtn.setOnClickListener {
            headerRow.visibility = View.GONE
            searchBarRow.visibility = View.VISIBLE
            searchInput.requestFocus()
        }

        closeSearchBtn.setOnClickListener {
            searchInput.text.clear() // Will trigger TextWatcher to reset list
            searchBarRow.visibility = View.GONE
            headerRow.visibility = View.VISIBLE
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderList(s?.toString() ?: "")
            }
        })

        // "+ Create a Group" Button
        val addBtn = Button(context).apply {
            text = "➕ Create a Group"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00A884"))
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { 
                setMargins(0, 30, 0, 0) 
            }
            setOnClickListener {
                val input = EditText(context).apply { hint = "Enter new group name..." }
                AlertDialog.Builder(context)
                    .setTitle("New Group")
                    .setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        val text = input.text.toString().trim()
                        if (text.isNotEmpty()) onGroupCreated(text)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        mainLayout.addView(addBtn)

        return mainLayout
    }
}
