package com.self.phonetodo.sync

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.self.phonetodo.data.GoogleOAuthTokens
import com.self.phonetodo.data.SettingsRepository
import com.self.phonetodo.data.TodoRepository
import com.self.phonetodo.model.TodoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Offline-first two-way sync using Google Drive REST API v3 (drive.file scope only).
 * Syncs PhoneDeskTodo/sync-data.json seamlessly with Windows DeskTodo.
 */
class GoogleDriveSyncManager(
    private val context: Context,
    private val todoRepository: TodoRepository,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val REDIRECT_URI = "com.self.phonetodo://oauth2callback"
        const val SCOPE = "https://www.googleapis.com/auth/drive.file email"
        const val SYNC_FOLDER_NAME = "PhoneDeskTodo"
        const val SYNC_FILE_NAME = "sync-data.json"
    }

    private val syncMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun startOAuthSignIn(activityContext: Context): Result<Unit> = runCatching {
        val clientId = settingsRepository.settings.value.googleClientId.trim()
        require(clientId.isNotEmpty()) { "请先填写 Google OAuth Client ID" }

        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        settingsRepository.saveTokens(
            settingsRepository.loadTokens().copy(codeVerifier = verifier),
        )

        val authUri = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()

        val customTabs = CustomTabsIntent.Builder().build()
        customTabs.launchUrl(activityContext, authUri)
    }

    suspend fun handleOAuthRedirect(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val error = uri.getQueryParameter("error")
            if (!error.isNullOrBlank()) error("Google 授权取消或失败：$error")
            val code = uri.getQueryParameter("code") ?: error("未收到授权码")

            val clientId = settingsRepository.settings.value.googleClientId.trim()
            val saved = settingsRepository.loadTokens()
            require(saved.codeVerifier.isNotBlank()) { "PKCE 校验码缺失，请重新点击登录" }

            val form = FormBody.Builder()
                .add("client_id", clientId)
                .add("code", code)
                .add("code_verifier", saved.codeVerifier)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", REDIRECT_URI)
                .build()

            val req = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(form)
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("Token 换取失败 (${resp.code}): $body")
                val obj = json.parseToJsonElement(body).jsonObject
                val access = obj["access_token"]?.jsonPrimitive?.content.orEmpty()
                val refresh = obj["refresh_token"]?.jsonPrimitive?.content ?: saved.refreshToken
                val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

                settingsRepository.saveTokens(
                    GoogleOAuthTokens(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresAtMs = System.currentTimeMillis() + (expiresIn - 60) * 1000L,
                        codeVerifier = "",
                    ),
                )

                val email = fetchUserEmail(access).orEmpty()
                settingsRepository.update {
                    it.copy(
                        googleSyncEnabled = true,
                        googleAccountEmail = email.ifBlank { "已授权 Google 账号" },
                        lastSyncStatus = "已登录，准备同步",
                    )
                }
                email.ifBlank { "已连接 Google Drive" }
            }
        }
    }

    fun signOut() {
        settingsRepository.clearTokens()
        settingsRepository.update {
            it.copy(
                googleSyncEnabled = false,
                googleAccountEmail = "",
                googleDriveFileId = "",
                lastSyncStatus = "已退出登录",
            )
        }
    }

    suspend fun syncNow(): Result<String> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val settings = settingsRepository.settings.value
                if (!settings.googleSyncEnabled || settings.googleClientId.isBlank()) {
                    return@runCatching "未启用 Google Drive 同步"
                }

                val accessToken = getValidAccessToken()
                val folderId = findOrCreateSyncFolder(accessToken)
                val existingFileId = findSyncFileId(accessToken, folderId)

                val local = todoRepository.data.value
                val merged: TodoData = if (existingFileId != null) {
                    val remoteJson = downloadFileContent(accessToken, existingFileId)
                    if (remoteJson.isNotBlank()) {
                        val remote = todoRepository.json.decodeFromString<TodoData>(remoteJson)
                        SyncMerger.merge(local, remote)
                    } else {
                        local
                    }
                } else {
                    local
                }

                val mergedJson = todoRepository.json.encodeToString(TodoData.serializer(), merged)
                val fileId = if (existingFileId != null) {
                    updateDriveFile(accessToken, existingFileId, mergedJson)
                    existingFileId
                } else {
                    createDriveFile(accessToken, folderId, mergedJson)
                }

                todoRepository.replaceFromSync(merged)

                val now = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                settingsRepository.update {
                    it.copy(
                        googleDriveFileId = fileId,
                        lastSyncAt = now,
                        lastSyncStatus = "同步成功 ($timeStr)",
                    )
                }
                "同步成功 ($timeStr)"
            }.onFailure { err ->
                settingsRepository.update {
                    it.copy(lastSyncStatus = "同步暂停：${err.message?.take(60) ?: "网络不可达"}")
                }
            }
        }
    }

    private fun getValidAccessToken(): String {
        val tokens = settingsRepository.loadTokens()
        if (tokens.accessToken.isNotBlank() && System.currentTimeMillis() < tokens.expiresAtMs) {
            return tokens.accessToken
        }
        require(tokens.refreshToken.isNotBlank()) { "请重新授权 Google 账号" }
        val clientId = settingsRepository.settings.value.googleClientId.trim()

        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", tokens.refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val req = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(form)
            .build()

        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("刷新登录凭证失败 (${resp.code})")
            val obj = json.parseToJsonElement(body).jsonObject
            val access = obj["access_token"]?.jsonPrimitive?.content.orEmpty()
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            settingsRepository.saveTokens(
                tokens.copy(
                    accessToken = access,
                    expiresAtMs = System.currentTimeMillis() + (expiresIn - 60) * 1000L,
                ),
            )
            return access
        }
    }

    private fun fetchUserEmail(accessToken: String): String? = runCatching {
        val req = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v2/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            json.parseToJsonElement(body).jsonObject["email"]?.jsonPrimitive?.content
        }
    }.getOrNull()

    private fun findOrCreateSyncFolder(token: String): String {
        val query = Uri.encode("name='$SYNC_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false")
        val listReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id,name)&pageSize=1")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(listReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val files = json.parseToJsonElement(body).jsonObject["files"]?.jsonArray
                val id = files?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                if (!id.isNullOrBlank()) return id
            }
        }

        val meta = """{"name":"$SYNC_FOLDER_NAME","mimeType":"application/vnd.google-apps.folder"}"""
        val createReq = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .header("Authorization", "Bearer $token")
            .post(meta.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(createReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("创建 Drive 目录失败 (${resp.code})")
            return json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content
                ?: error("创建 Drive 目录未返回 ID")
        }
    }

    private fun findSyncFileId(token: String, folderId: String): String? {
        val query = Uri.encode("name='$SYNC_FILE_NAME' and '$folderId' in parents and trashed=false")
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id,modifiedTime)&pageSize=1")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("查询同步文件失败 (${resp.code})")
            val files = json.parseToJsonElement(body).jsonObject["files"]?.jsonArray
            return files?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
        }
    }

    private fun downloadFileContent(token: String, fileId: String): String {
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载同步文件失败 (${resp.code})")
            return resp.body?.string().orEmpty()
        }
    }

    private fun createDriveFile(token: String, folderId: String, content: String): String {
        val metaJson = """{"name":"$SYNC_FILE_NAME","parents":["$folderId"],"mimeType":"application/json"}"""
        val multipart = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metaJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addPart(content.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("上传同步文件失败 (${resp.code})")
            return json.parseToJsonElement(body).jsonObject["id"]?.jsonPrimitive?.content.orEmpty()
        }
    }

    private fun updateDriveFile(token: String, fileId: String, content: String) {
        val req = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media")
            .header("Authorization", "Bearer $token")
            .patch(content.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("更新同步文件失败 (${resp.code})")
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
