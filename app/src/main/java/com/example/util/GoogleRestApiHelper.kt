package com.example.util

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

/**
 * A highly robust REST API Client implementing full support for Google Calendar v3,
 * Google Keep v1, and Google Docs v1 as defined in the provided API Reference.
 */
object GoogleRestApiHelper {
    private const val TAG = "GoogleRestApiHelper"
    private val client = OkHttpClient()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // Base URLs
    private const val CALENDAR_BASE_URL = "https://www.googleapis.com/calendar/v3"
    private const val KEEP_BASE_URL = "https://keep.googleapis.com"
    private const val DOCS_BASE_URL = "https://docs.googleapis.com"

    /**
     * Retrieves an OAuth2 access token for the given scopes.
     */
    suspend fun getAccessTokenForScopes(
        context: Context,
        scopes: List<String>,
        onAuthResolutionRequired: (Intent) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            var email = prefs.getString("selected_file_backup_account", null)
            if (email.isNullOrBlank()) {
                val account = GoogleSignIn.getLastSignedInAccount(context)
                email = account?.email
            }
            if (email.isNullOrBlank()) {
                Log.w(TAG, "No Google account email found for oauth token retrieval.")
                return@withContext null
            }
            val scopeString = "oauth2:" + scopes.joinToString(" ")
            GoogleAuthUtil.getToken(context, email, scopeString)
        } catch (recoverable: UserRecoverableAuthException) {
            Log.w(TAG, "User recoverable auth exception encountered during token retrieval.", recoverable)
            recoverable.intent?.let { intent ->
                withContext(Dispatchers.Main) {
                    onAuthResolutionRequired(intent)
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining Google OAuth2 token for REST calls: ${e.message}", e)
            null
        }
    }

    // ==========================================
    // 1. GOOGLE CALENDAR v3 REST API METHODS
    // ==========================================

    // --- Acl Methods ---
    suspend fun deleteAcl(token: String, calendarId: String, ruleId: String): Pair<Boolean, String> =
        executeDelete("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/acl/${URLEncoder.encode(ruleId, "UTF-8")}", token)

    suspend fun getAcl(token: String, calendarId: String, ruleId: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/acl/${URLEncoder.encode(ruleId, "UTF-8")}", token)

    suspend fun insertAcl(token: String, calendarId: String, ruleJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/acl", ruleJson, token)

    suspend fun listAcl(token: String, calendarId: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/acl", token)

    suspend fun patchAcl(token: String, calendarId: String, ruleId: String, ruleJson: String): Pair<Boolean, String> =
        executePatch("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/acl/${URLEncoder.encode(ruleId, "UTF-8")}", ruleJson, token)

    suspend fun updateAcl(token: String, calendarId: String, ruleId: String, ruleJson: String): Pair<Boolean, String> =
        executePut("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/acl/${URLEncoder.encode(ruleId, "UTF-8")}", ruleJson, token)

    suspend fun watchAcl(token: String, calendarId: String, watchJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/acl/watch", watchJson, token)

    // --- CalendarList Methods ---
    suspend fun deleteCalendarList(token: String, calendarId: String): Pair<Boolean, String> =
        executeDelete("$CALENDAR_BASE_URL/users/me/calendarList/${URLEncoder.encode(calendarId, "UTF-8")}", token)

    suspend fun getCalendarList(token: String, calendarId: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/users/me/calendarList/${URLEncoder.encode(calendarId, "UTF-8")}", token)

    suspend fun insertCalendarList(token: String, calendarListJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/users/me/calendarList", calendarListJson, token)

    suspend fun listCalendarList(token: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/users/me/calendarList", token)

    suspend fun patchCalendarList(token: String, calendarId: String, calendarListJson: String): Pair<Boolean, String> =
        executePatch("$CALENDAR_BASE_URL/users/me/calendarList/${URLEncoder.encode(calendarId, "UTF-8")}", calendarListJson, token)

    suspend fun updateCalendarList(token: String, calendarId: String, calendarListJson: String): Pair<Boolean, String> =
        executePut("$CALENDAR_BASE_URL/users/me/calendarList/${URLEncoder.encode(calendarId, "UTF-8")}", calendarListJson, token)

    suspend fun watchCalendarList(token: String, watchJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/users/me/calendarList/watch", watchJson, token)

    // --- Calendars Methods ---
    suspend fun clearCalendar(token: String, calendarId: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/clear", "", token)

    suspend fun deleteCalendar(token: String, calendarId: String): Pair<Boolean, String> =
        executeDelete("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}", token)

    suspend fun getCalendarMetadata(token: String, calendarId: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}", token)

    suspend fun insertCalendar(token: String, calendarJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/calendars", calendarJson, token)

    suspend fun patchCalendar(token: String, calendarId: String, calendarJson: String): Pair<Boolean, String> =
        executePatch("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}", calendarJson, token)

    suspend fun transferOwnership(token: String, calendarId: String, newDataOwner: String, useAdminAccess: Boolean = true): Pair<Boolean, String> {
        val url = "$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/transferOwnership" +
                "?newDataOwner=${URLEncoder.encode(newDataOwner, "UTF-8")}&useAdminAccess=$useAdminAccess"
        return executePost(url, "", token)
    }

    suspend fun updateCalendar(token: String, calendarId: String, calendarJson: String): Pair<Boolean, String> =
        executePut("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}", calendarJson, token)

    // --- Channels Methods ---
    suspend fun stopChannel(token: String, channelJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/channels/stop", channelJson, token)

    // --- Colors Methods ---
    suspend fun getColors(token: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/colors", token)

    // --- Events Methods ---
    suspend fun deleteEvent(token: String, calendarId: String, eventId: String): Pair<Boolean, String> =
        executeDelete("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events/${URLEncoder.encode(eventId, "UTF-8")}", token)

    suspend fun getEvent(token: String, calendarId: String, eventId: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events/${URLEncoder.encode(eventId, "UTF-8")}", token)

    suspend fun importEvent(token: String, calendarId: String, eventJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events/import", eventJson, token)

    suspend fun insertEvent(token: String, calendarId: String, eventJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events", eventJson, token)

    suspend fun listEventInstances(token: String, calendarId: String, eventId: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events/${URLEncoder.encode(eventId, "UTF-8")}/instances", token)

    suspend fun listEvents(token: String, calendarId: String, queryParams: Map<String, String> = emptyMap()): Pair<Boolean, String> {
        val queryBuilder = StringBuilder()
        if (queryParams.isNotEmpty()) {
            queryBuilder.append("?")
            queryParams.forEach { (key, value) ->
                queryBuilder.append(URLEncoder.encode(key, "UTF-8"))
                    .append("=")
                    .append(URLEncoder.encode(value, "UTF-8"))
                    .append("&")
            }
            queryBuilder.setLength(queryBuilder.length - 1) // Trim last ampersand
        }
        val url = "$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events$queryBuilder"
        return executeGet(url, token)
    }

    suspend fun moveEvent(token: String, calendarId: String, eventId: String, destination: String): Pair<Boolean, String> {
        val url = "$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events/${URLEncoder.encode(eventId, "UTF-8")}/move" +
                "?destination=${URLEncoder.encode(destination, "UTF-8")}"
        return executePost(url, "", token)
    }

    suspend fun patchEvent(token: String, calendarId: String, eventId: String, eventJson: String): Pair<Boolean, String> =
        executePatch("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events/${URLEncoder.encode(eventId, "UTF-8")}", eventJson, token)

    suspend fun quickAddEvent(token: String, calendarId: String, text: String): Pair<Boolean, String> {
        val url = "$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events/quickAdd" +
                "?text=${URLEncoder.encode(text, "UTF-8")}"
        return executePost(url, "", token)
    }

    suspend fun updateEvent(token: String, calendarId: String, eventId: String, eventJson: String): Pair<Boolean, String> =
        executePut("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events/${URLEncoder.encode(eventId, "UTF-8")}", eventJson, token)

    suspend fun watchEvents(token: String, calendarId: String, watchJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/calendars/${URLEncoder.encode(calendarId, "UTF-8")}/events/watch", watchJson, token)

    // --- Freebusy Methods ---
    suspend fun queryFreebusy(token: String, queryJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/freeBusy", queryJson, token)

    // --- Settings Methods ---
    suspend fun getSetting(token: String, setting: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/users/me/settings/${URLEncoder.encode(setting, "UTF-8")}", token)

    suspend fun listSettings(token: String): Pair<Boolean, String> =
        executeGet("$CALENDAR_BASE_URL/users/me/settings", token)

    suspend fun watchSettings(token: String, watchJson: String): Pair<Boolean, String> =
        executePost("$CALENDAR_BASE_URL/users/me/settings/watch", watchJson, token)


    // ==========================================
    // 2. GOOGLE KEEP v1 REST API METHODS
    // ==========================================

    // --- Media Methods ---
    suspend fun downloadKeepMedia(token: String, attachmentName: String): Pair<Boolean, ByteArray> = withContext(Dispatchers.IO) {
        try {
            val url = "$KEEP_BASE_URL/v1/${attachmentName.trimStart('/')}"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed downloadKeepMedia: code=${response.code}")
                    Pair(false, ByteArray(0))
                } else {
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    Pair(true, bytes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading Keep media: ${e.message}", e)
            Pair(false, ByteArray(0))
        }
    }

    // --- Notes Methods ---
    suspend fun createKeepNote(token: String, noteJson: String): Pair<Boolean, String> =
        executePost("$KEEP_BASE_URL/v1/notes", noteJson, token)

    suspend fun deleteKeepNote(token: String, noteName: String): Pair<Boolean, String> =
        executeDelete("$KEEP_BASE_URL/v1/${noteName.trimStart('/')}", token)

    suspend fun getKeepNote(token: String, noteName: String): Pair<Boolean, String> =
        executeGet("$KEEP_BASE_URL/v1/${noteName.trimStart('/')}", token)

    suspend fun listKeepNotes(token: String): Pair<Boolean, String> =
        executeGet("$KEEP_BASE_URL/v1/notes", token)

    // --- Permissions Methods ---
    suspend fun batchCreatePermissions(token: String, noteName: String, permissionsJson: String): Pair<Boolean, String> =
        executePost("$KEEP_BASE_URL/v1/${noteName.trimStart('/')}/permissions:batchCreate", permissionsJson, token)

    suspend fun batchDeletePermissions(token: String, noteName: String, permissionsJson: String): Pair<Boolean, String> =
        executePost("$KEEP_BASE_URL/v1/${noteName.trimStart('/')}/permissions:batchDelete", permissionsJson, token)


    // ==========================================
    // 3. GOOGLE DOCS v1 REST API METHODS
    // ==========================================

    // --- Documents Methods ---
    suspend fun batchUpdateDocument(token: String, documentId: String, requestsJson: String): Pair<Boolean, String> =
        executePost("$DOCS_BASE_URL/v1/documents/${URLEncoder.encode(documentId, "UTF-8")}:batchUpdate", requestsJson, token)

    suspend fun createDocument(token: String, documentJson: String): Pair<Boolean, String> =
        executePost("$DOCS_BASE_URL/v1/documents", documentJson, token)

    suspend fun getDocument(token: String, documentId: String): Pair<Boolean, String> =
        executeGet("$DOCS_BASE_URL/v1/documents/${URLEncoder.encode(documentId, "UTF-8")}", token)


    // ==========================================
    // HTTP VERB EXECUTORS
    // ==========================================

    private suspend fun executeGet(url: String, token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "GET $url Failed: code=${response.code} body=$bodyStr")
                    Pair(false, bodyStr)
                } else {
                    Pair(true, bodyStr)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GET $url Error: ${e.message}", e)
            Pair(false, e.localizedMessage ?: "Unknown Error")
        }
    }

    private suspend fun executePost(url: String, json: String, token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "POST $url Failed: code=${response.code} body=$bodyStr")
                    Pair(false, bodyStr)
                } else {
                    Pair(true, bodyStr)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "POST $url Error: ${e.message}", e)
            Pair(false, e.localizedMessage ?: "Unknown Error")
        }
    }

    private suspend fun executePut(url: String, json: String, token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .put(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "PUT $url Failed: code=${response.code} body=$bodyStr")
                    Pair(false, bodyStr)
                } else {
                    Pair(true, bodyStr)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PUT $url Error: ${e.message}", e)
            Pair(false, e.localizedMessage ?: "Unknown Error")
        }
    }

    private suspend fun executePatch(url: String, json: String, token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .patch(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "PATCH $url Failed: code=${response.code} body=$bodyStr")
                    Pair(false, bodyStr)
                } else {
                    Pair(true, bodyStr)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PATCH $url Error: ${e.message}", e)
            Pair(false, e.localizedMessage ?: "Unknown Error")
        }
    }

    private suspend fun executeDelete(url: String, token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.e(TAG, "DELETE $url Failed: code=${response.code} body=$bodyStr")
                    Pair(false, bodyStr)
                } else {
                    Pair(true, bodyStr)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "DELETE $url Error: ${e.message}", e)
            Pair(false, e.localizedMessage ?: "Unknown Error")
        }
    }
}
