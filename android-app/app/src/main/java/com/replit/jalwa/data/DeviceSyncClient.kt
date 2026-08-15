package com.replit.jalwa.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.replit.jalwa.BuildConfig
import com.replit.jalwa.detection.TestAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RemoteCommand(
    val id: String,
    val type: String,
    val action: TestAction,
    val templateName: String?,
)

data class HeartbeatResult(
    val commands: List<RemoteCommand>,
)

/**
 * Small, dependency-free client for the shared fleet API.
 *
 * The base URL is injected at build time with:
 * ./gradlew assembleDebug -PcontrolApiBaseUrl=https://your-domain/api
 */
class DeviceSyncClient(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val baseUrl = BuildConfig.CONTROL_API_BASE_URL.trimEnd('/')

    suspend fun heartbeat(
        isRunning: Boolean,
        action: TestAction,
        batteryLevel: Int?,
    ): HeartbeatResult? = runCatching {
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) return@withContext null

            val deviceId = deviceId()
            var token = preferences.getString(TOKEN_KEY, null)
            if (token == null) {
                token = enroll(deviceId) ?: return@withContext null
            }

            val body = JSONObject()
                .put("model", Build.MODEL)
                .put("androidVersion", "Android ${Build.VERSION.RELEASE}")
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("batteryLevel", batteryLevel)
                .put("activeAction", action.name)
                .put("isRunning", isRunning)

            val response = request(
                method = "POST",
                path = "/device-agent/$deviceId/heartbeat",
                body = body.toString(),
                headers = mapOf("X-Device-Token" to token),
            )
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                preferences.edit().remove(TOKEN_KEY).apply()
                return@withContext null
            }
            if (response.code !in 200..299) return@withContext null

            val json = JSONObject(response.body)
            val commands = parseCommands(json.optJSONArray("commands") ?: JSONArray())
            HeartbeatResult(commands)
        }
    }.getOrNull()

    private fun enroll(deviceId: String): String? {
        val body = JSONObject()
            .put("id", deviceId)
            .put("displayName", "ATPILOT · ${Build.MODEL}")
            .put("model", Build.MODEL)
            .put("androidVersion", "Android ${Build.VERSION.RELEASE}")
            .put("appVersion", BuildConfig.VERSION_NAME)
        val response = request("POST", "/devices", body.toString())
        if (response.code !in 200..299) return null
        val token = JSONObject(response.body).optString("deviceToken").takeIf { it.isNotBlank() }
        token?.let { preferences.edit().putString(TOKEN_KEY, it).apply() }
        return token
    }

    private fun parseCommands(json: JSONArray): List<RemoteCommand> =
        buildList {
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                add(
                    RemoteCommand(
                        id = item.optString("id"),
                        type = item.optString("type"),
                        action = runCatching { TestAction.valueOf(item.optString("action", "NONE")) }
                            .getOrDefault(TestAction.NONE),
                        templateName = item.optString("templateName").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            doInput = true
            setRequestProperty("Accept", "application/json")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val responseBody = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            Response(connection.responseCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun deviceId(): String =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "android-${Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"

    private data class Response(val code: Int, val body: String)

    private companion object {
        const val PREFERENCES = "atpilot_device_sync"
        const val TOKEN_KEY = "device_token"
    }
}