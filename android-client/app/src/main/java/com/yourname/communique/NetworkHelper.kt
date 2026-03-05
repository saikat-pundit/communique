package com.yourname.communique

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class NetworkHelper(private val httpClient: OkHttpClient, private val gson: Gson) {

    fun fetchChatHistory(): List<ChatMessage>? {
        // FIX 1: Add cache buster and headers to force GitHub to send the latest reverted messages
        val request = Request.Builder()
            .url("https://api.github.com/gists/${BuildConfig.CHAT_GIST_ID}?t=${System.currentTimeMillis()}")
            .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
            .addHeader("Cache-Control", "no-store, no-cache")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    if (responseData.isNullOrEmpty()) return@use null
                    
                    val content = JSONObject(responseData)
                        .getJSONObject("files")
                        .getJSONObject("chat_ledger.json")
                        .getString("content")
                    
                    // FIX 2: Explicitly force Kotlin to parse the list type correctly
                    val type = object : TypeToken<List<ChatMessage>>() {}.type
                    val fetchedList: List<ChatMessage>? = gson.fromJson(content, type)
                    fetchedList ?: emptyList()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun pushGistUpdate(history: List<ChatMessage>) {
        // --- AUTO-ARCHIVER PAGINATION ---
        if (history.size > 500) {
            val archiveCount = 400
            val messagesToArchive = history.take(archiveCount)
            val messagesToKeep = history.drop(archiveCount)

            val archiveSuccess = safePushToArchive(messagesToArchive)
            
            if (archiveSuccess) {
                // Archive secured! Safe to wipe them from the main fast-loading Gist.
                pushToMainGist(messagesToKeep)
                return 
            }
            // If archive fails, we fall through to the standard push to prevent data loss.
        }
        // --------------------------------

        pushToMainGist(history)
    }

    private fun pushToMainGist(history: List<ChatMessage>) {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun safePushToArchive(newArchiveMessages: List<ChatMessage>): Boolean {
        return try {
            // 1. Fetch current Archive so we don't overwrite it
            val fetchReq = Request.Builder()
                .url("https://api.github.com/gists/${BuildConfig.ARCHIVE_GIST_ID}?t=${System.currentTimeMillis()}")
                .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
                .addHeader("Cache-Control", "no-store, no-cache")
                .build()

            val currentArchiveList = mutableListOf<ChatMessage>()
            httpClient.newCall(fetchReq).execute().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body?.string()
                    if (!responseData.isNullOrEmpty()) {
                        val content = JSONObject(responseData).getJSONObject("files").optJSONObject("archive_ledger.json")?.optString("content")
                        if (content != null && content != "[]") {
                            val type = object : TypeToken<List<ChatMessage>>() {}.type
                            val fetched: List<ChatMessage>? = gson.fromJson(content, type)
                            if (fetched != null) currentArchiveList.addAll(fetched)
                        }
                    }
                }
            }

            // 2. Append the new 400 messages to the existing archive history
            currentArchiveList.addAll(newArchiveMessages)

            // 3. Push the newly combined archive back to the Archive Gist
            val payload = JSONObject().apply {
                put("files", JSONObject().apply {
                    put("archive_ledger.json", JSONObject().apply {
                        put("content", gson.toJson(currentArchiveList))
                    })
                })
            }
            val pushReq = Request.Builder()
                .url("https://api.github.com/gists/${BuildConfig.ARCHIVE_GIST_ID}")
                .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
                .patch(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(pushReq).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false // Archiving failed, tell the main system to abort deletion!
        }
    }
}
