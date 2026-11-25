package com.self.phonetodo.sync

import com.self.phonetodo.data.SettingsRepository
import com.self.phonetodo.data.TodoRepository
import com.self.phonetodo.model.TodoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
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
 * - Strictly enforces that the target repository is private (`private == true`) before connecting or uploading.
 * - Re-merges against the latest local state inside TodoRepository's lock after every upload and loops if
 *   the user edited a todo while the HTTP request was in flight (zero lost updates).
 * - Shares the exact v3 TodoData JSON + LWW/tombstone merge contract with Windows DeskTodo.
 */
class GithubSyncManager(
    private val todoRepository: TodoRepository,
    private val settingsRepository: SettingsRepository,
) {
    internal data class RemoteFile(
        val sha: String,
        val data: TodoData?,
    )

    internal class ShaConflictException(msg: String) : Exception(msg)

    internal interface SyncTransport {
        fun verifyPrivateRepo(repo: String, token: String)
        fun fetchRemoteFile(repo: String, token: String): RemoteFile?
        fun putRemoteFile(repo: String, token: String, content: String, sha: String?)
    }

    companion object {
        const val SYNC_FILE_PATH = "sync-data.json"
        private val REPO_REGEX = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
        private val metaJson = Json { ignoreUnknownKeys = true }

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

        /** Parses GitHub `/repos/{owner}/{repo}` JSON body and returns whether `"private"` is true. */
        fun parseRepoIsPrivate(responseBody: String): Boolean? = runCatching {
            metaJson.parseToJsonElement(responseBody).jsonObject["private"]?.jsonPrimitive?.booleanOrNull
        }.getOrNull()

        /**
         * Core conflict-safe sync loop (extracted for direct regression testing):
         * 1. Fetch remote & merge against local snapshot.
         * 2. Verify repository is still private before uploading; retry on SHA conflict.
         * 3. Atomically re-merge with the LATEST local state inside TodoRepository's lock.
         * 4. If new local edits arrived during the HTTP upload, perform another round so nothing is lost or stranded.
         */
        internal fun runSyncLoop(
            repo: String,
            token: String,
            todoRepository: TodoRepository,
            transport: SyncTransport,
            maxRounds: Int = 3,
        ): Boolean {
            var uploadedAny = false
            for (round in 0 until maxRounds) {
                val remoteFile = transport.fetchRemoteFile(repo, token)
                val localSnapshot = todoRepository.data.value
                val remoteData = remoteFile?.data

                val merged = if (remoteData != null) {
                    SyncMerger.merge(localSnapshot, remoteData)
                } else {
                    localSnapshot
                }

                val remoteUnchanged = remoteData != null && isSemanticallyEqual(merged, remoteData)
                if (!remoteUnchanged) {
                    // Always verify privacy right before uploading personal todo contents
                    transport.verifyPrivateRepo(repo, token)
                    val serialized = todoRepository.json.encodeToString(TodoData.serializer(), merged)
                    try {
                        transport.putRemoteFile(repo, token, serialized, remoteFile?.sha)
                        uploadedAny = true
                    } catch (e: ShaConflictException) {
                        if (round == maxRounds - 1) throw e
                        continue
                    }
                }

                // Atomically merge against whatever local state exists RIGHT NOW (never clobber concurrent edits!)
                val postLocal = todoRepository.mergeFromSync(merged)

                // If user mutated a todo while the HTTP request was in flight, run one more round to push it too
                if (isSemanticallyEqual(postLocal, merged)) {
                    return uploadedAny
                }
            }
            return uploadedAny
        }
    }

    private val syncMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val httpTransport = object : SyncTransport {
        override fun verifyPrivateRepo(repo: String, token: String) = verifyRepoAccess(repo, token)
        override fun fetchRemoteFile(repo: String, token: String): RemoteFile? = this@GithubSyncManager.fetchRemoteFile(repo, token)
        override fun putRemoteFile(repo: String, token: String, content: String, sha: String?) =
            this@GithubSyncManager.putRemoteFile(repo, token, content, sha)
    }

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
            val err = verifyResult.exceptionOrNull()!!
            settingsRepository.update {
                it.copy(lastSyncStatus = "连接被拒绝：${err.message?.take(80) ?: "校验失败"}")
            }
            return Result.failure(err)
        }

        settingsRepository.saveGithubToken(token)
        settingsRepository.update {
            it.copy(
                githubSyncRepo = repo,
                githubSyncEnabled = true,
                lastSyncStatus = "私有仓库已验证，正在首次同步…",
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

                val uploaded = runSyncLoop(
                    repo = repo,
                    token = token,
                    todoRepository = todoRepository,
                    transport = httpTransport,
                )

                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val statusMsg = if (uploaded) "同步成功 ($timeStr)" else "已是最新 ($timeStr)"
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
                    it.copy(lastSyncStatus = "同步暂停：${err.message?.take(80) ?: "网络不可达"}")
                }
            }
        }
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
                in 200..299 -> {
                    val body = resp.body?.string().orEmpty()
                    val isPrivate = parseRepoIsPrivate(body)
                    if (isPrivate != true) {
                        error("安全拦截：仓库 $repo 不是私有仓库，已禁止上传待办数据。请在 GitHub 将其改为 Private 后重试")
                    }
                }
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
                    // Distinguish between missing file (brand new sync) and missing/unauthorized/public repo
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
