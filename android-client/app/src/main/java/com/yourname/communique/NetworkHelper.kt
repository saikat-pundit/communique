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
}
