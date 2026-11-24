package com.self.phonetodo.sync

import com.self.phonetodo.data.SettingsRepository
import com.self.phonetodo.data.TodoRepository
import com.self.phonetodo.model.TodoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Two-way private sync via a GitHub repository Contents API (`sync-data.json`).
 * Shares the exact v3 TodoData JSON + LWW/tombstone merge contract with Windows DeskTodo.
 */
class GithubSyncManager(
    private val todoRepository: TodoRepository,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val SYNC_FILE_PATH = "sync-data.json"
        private val REPO_REGEX = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")

        fun normalizeRepo(raw: String): String? {
            val cleaned = raw.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .removeSuffix(".git")
                .trim('/')
            return cleaned.takeIf { REPO_REGEX.matches(it) }
        }

        fun isSemanticallyEqual(a: TodoData, b: TodoData): Boolean {
            if (a.todos != b.todos) return false
            val catsA = a.categories.map { it.copy(collapsed = false) }
            val catsB = b.categories.map { it.copy(collapsed = false) }
            return catsA == catsB
        }
    }

    private data class RemoteFile(
        val sha: String,
        val data: TodoData?,
    )

    private class ShaConflictException(msg: String) : Exception(msg)

    private val syncMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun connectAndSync(rawRepo: String, rawToken: String): Result<String> {
        val repo = normalizeRepo(rawRepo)
            ?: return Result.failure(IllegalArgumentException("仓库格式应为 用户名/仓库名（例如 haaharden/todo-sync）"))
        val token = rawToken.trim().ifBlank { settingsRepository.loadGithubToken() }
        if (token.isBlank()) {
            return Result.failure(IllegalArgumentException("请填写 GitHub Personal Access Token"))
        }

        val verifyResult = withContext(Dispatchers.IO) {
            runCatching { verifyRepoAccess(repo, token) }
        }
        if (verifyResult.isFailure) {
            return Result.failure(verifyResult.exceptionOrNull()!!)
        }

        settingsRepository.saveGithubToken(token)
        settingsRepository.update {
            it.copy(
                githubSyncRepo = repo,
                githubSyncEnabled = true,
                lastSyncStatus = "仓库已连接，正在首次同步…",
            )
        }
        return syncNow()
    }

    fun disconnect() {
        settingsRepository.clearGithubToken()
        settingsRepository.update {
            it.copy(
                githubSyncEnabled = false,
                lastSyncStatus = "已断开同步",
            )
        }
    }

    suspend fun syncNow(): Result<String> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val settings = settingsRepository.settings.value
                val repo = normalizeRepo(settings.githubSyncRepo)
                val token = settingsRepository.loadGithubToken()
                if (!settings.githubSyncEnabled || repo == null || token.isBlank()) {
                    return@runCatching "未启用 GitHub 同步"
                }

                val statusMsg = try {
                    syncOnce(repo, token)
                } catch (_: ShaConflictException) {
                    // Concurrent push from DeskTodo — retry once with the fresh remote sha
                    syncOnce(repo, token)
                }

                val now = System.currentTimeMillis()
                settingsRepository.update {
                    it.copy(
                        lastSyncAt = now,
                        lastSyncStatus = statusMsg,
                    )
                }
                statusMsg
            }.onFailure { err ->
                settingsRepository.update {
                    it.copy(lastSyncStatus = "同步暂停：${err.message?.take(60) ?: "网络不可达"}")
                }
            }
        }
    }

    private fun syncOnce(repo: String, token: String): String {
        val remoteFile = fetchRemoteFile(repo, token)
        val local = todoRepository.data.value
        val remoteData = remoteFile?.data

        val merged = if (remoteData != null) {
            SyncMerger.merge(local, remoteData)
        } else {
            local
        }

        val remoteUnchanged = remoteData != null && isSemanticallyEqual(merged, remoteData)
        if (!remoteUnchanged) {
            val serialized = todoRepository.json.encodeToString(TodoData.serializer(), merged)
            putRemoteFile(repo, token, serialized, remoteFile?.sha)
        }

        if (!isSemanticallyEqual(local, merged)) {
            todoRepository.replaceFromSync(merged)
        }

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return if (remoteUnchanged) "已是最新 ($timeStr)" else "同步成功 ($timeStr)"
    }

    private fun baseRequest(url: String, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "PhoneToDo-Sync")

    private fun verifyRepoAccess(repo: String, token: String) {
        val req = baseRequest("https://api.github.com/repos/$repo", token).get().build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                in 200..299 -> Unit
                401 -> error("Token 无效或已过期")
                403 -> error("Token 权限不足，请勾选该仓库 Contents 读写权限")
                404 -> error("找不到仓库 $repo，请先在 GitHub 创建该私有仓库并授权 Token")
                else -> error("校验仓库失败 (${resp.code})")
            }
        }
    }

    private fun fetchRemoteFile(repo: String, token: String): RemoteFile? {
        val req = baseRequest("https://api.github.com/repos/$repo/contents/$SYNC_FILE_PATH", token)
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                404 -> {
                    // Distinguish between missing file (brand new sync) and missing/unauthorized repo
                    verifyRepoAccess(repo, token)
                    return null
                }
                401 -> error("Token 无效或已过期")
                403 -> error("Token 缺少 Contents 读取权限")
                !in 200..299 -> error("读取云端数据失败 (${resp.code})")
            }

            val body = resp.body?.string().orEmpty()
            val obj = json.parseToJsonElement(body).jsonObject
            val sha = obj["sha"]?.jsonPrimitive?.content.orEmpty()
            val rawBase64 = obj["content"]?.jsonPrimitive?.content.orEmpty()
            val rawJson = if (rawBase64.isNotBlank()) {
                String(Base64.getMimeDecoder().decode(rawBase64), Charsets.UTF_8)
            } else {
                // Fallback for files > 1 MB where GitHub omits inline content
                val downloadUrl = obj["download_url"]?.jsonPrimitive?.content.orEmpty()
                if (downloadUrl.isNotBlank()) downloadRawUrl(downloadUrl, token) else ""
            }

            val parsed = rawJson.takeIf { it.isNotBlank() }?.let {
                todoRepository.json.decodeFromString<TodoData>(it)
            }
            return RemoteFile(sha = sha, data = parsed)
        }
    }

    private fun downloadRawUrl(url: String, token: String): String {
        val req = baseRequest(url, token).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载云端大文件失败 (${resp.code})")
            return resp.body?.string().orEmpty()
        }
    }

    private fun putRemoteFile(repo: String, token: String, content: String, sha: String?) {
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val payload = buildJsonObject {
            put("message", "sync(phone): $stamp")
            put("content", encoded)
            if (!sha.isNullOrBlank()) {
                put("sha", sha)
            }
        }.toString()

        val req = baseRequest("https://api.github.com/repos/$repo/contents/$SYNC_FILE_PATH", token)
            .put(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                in 200..299 -> Unit
                409, 422 -> throw ShaConflictException("远端已更新 (${resp.code})")
                401 -> error("Token 无效或已过期")
                403 -> error("Token 缺少 Contents 写入权限")
                404 -> error("无法写入仓库 $repo，请检查 Token 权限")
                else -> error("上传云端数据失败 (${resp.code})")
            }
        }
    }
}
