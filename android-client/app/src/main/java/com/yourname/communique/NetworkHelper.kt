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
        val request = Request.Builder()
            .url("https://api.github.com/gists/${BuildConfig.CHAT_GIST_ID}")
            .addHeader("Authorization", "Bearer ${BuildConfig.GIST_TOKEN}")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body?.string() ?: return null
                    val content = JSONObject(responseData).getJSONObject("files").getJSONObject("chat_ledger.json").getString("content")
                    gson.fromJson(content, object : TypeToken<List<ChatMessage>>() {}.type)
                } else null
            }
        } catch (e: Exception) {
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
        } catch (e: Exception) {}
    }
}
