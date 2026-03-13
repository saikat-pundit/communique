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
        unreadCounts: Map<String, Int>, // Re-added this
        onGroupSelected: (String) -> Unit,
        onGroupCreated: (String) -> Unit,
        onGroupRename: (String, String) -> Unit, // Re-added this
        onGroupDelete: (String) -> Unit          // Re-added this
    ): LinearLayout {
        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundResource(R.drawable.group) // Restored your group.png wallpaper
            setPadding(40, 80, 40, 40)
        }

        // Header Row with Title & Search Icon
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

        // Hidden Search Bar Row
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

        val allGroups = chatHistory.map { it.groupName ?: "Personal Chat" }
            .distinct()
            .filter { it != "Personal Chat" }
            .toMutableList()
        
        allGroups.add(0, "Personal Chat")

        // Dynamic Render Function
        fun renderList(query: String) {
            listLayout.removeAllViews()
            val filtered = allGroups.filter { it.contains(query, ignoreCase = true) }
            
            filtered.forEach { group ->
                // Restored horizontal layout for Unread Badges
                val groupContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(50, 50, 50, 50)
                    background = GradientDrawable().apply {
                        setColor(if (group == "Personal Chat") Color.parseColor("#00FFFF") else Color.WHITE)
                        cornerRadius = 24f
                    }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { 
                        setMargins(0, 0, 0, 20) 
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onGroupSelected(group) }
                    
                    // Restored Long Press for Rename/Delete
                    if (group != "Personal Chat") {
                        setOnLongClickListener {
                            val optionsDialog = AlertDialog.Builder(context).create()
                            val optionsLayout = LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(40, 40, 40, 40)
                                background = GradientDrawable().apply {
                                    setColor(Color.WHITE)
                                    cornerRadius = 32f
                                }
                            }
                            
                            val titleText = TextView(context).apply {
                                text = "Group Options"
                                textSize = 20f
                                setTypeface(null, Typeface.BOLD)
                                setPadding(0, 0, 0, 30)
                            }
                            optionsLayout.addView(titleText)
                            
                            val renameBtn = TextView(context).apply {
                                text = "✏️ Rename Group"
                                textSize = 18f
                                setPadding(20, 30, 20, 30)
                                setOnClickListener {
                                    optionsDialog.dismiss()
                                    val input = EditText(context).apply { setText(group) }
                                    AlertDialog.Builder(context)
                                        .setTitle("Rename Group")
                                        .setView(input)
                                        .setPositiveButton("Save") { _, _ ->
                                            val newName = input.text.toString().trim()
                                            if (newName.isNotEmpty() && newName != group) {
                                                onGroupRename(group, newName)
                                            }
                                        }
                                        .setNegativeButton("Cancel", null)
                                        .show()
                                }
                            }
                            optionsLayout.addView(renameBtn)
                            
                            val deleteBtn = TextView(context).apply {
                                text = "🗑️ Delete Group"
                                textSize = 18f
                                setTextColor(Color.RED)
                                setPadding(20, 30, 20, 30)
                                setOnClickListener {
                                    optionsDialog.dismiss()
                                    onGroupDelete(group)
                                }
                            }
                            optionsLayout.addView(deleteBtn)
                            
                            optionsDialog.setView(optionsLayout)
                            optionsDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                            optionsDialog.show()
                            
                            true
                        }
                    }
                }

                val groupNameText = TextView(context).apply {
                    text = "📁  $group"
                    textSize = 18f
                    setTextColor(Color.BLACK)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                groupContainer.addView(groupNameText)

                // Restored Unread Badge Logic
                val unread = unreadCounts[group] ?: 0
                if (unread > 0) {
                    val badge = TextView(context).apply {
                        text = unread.toString()
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setPadding(20, 6, 20, 6)
                        minWidth = 75
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#0B7065"))
                            cornerRadius = 50f
                        }
                    }
                    groupContainer.addView(badge)
                }
                
                listLayout.addView(groupContainer)
            }
        }

        // Initial render
        renderList("")
        scrollView.addView(listLayout)
        mainLayout.addView(scrollView)

        // Search Interactions
        searchBtn.setOnClickListener {
            headerRow.visibility = View.GONE
            searchBarRow.visibility = View.VISIBLE
            searchInput.requestFocus()
        }

        closeSearchBtn.setOnClickListener {
            searchInput.text.clear()
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
