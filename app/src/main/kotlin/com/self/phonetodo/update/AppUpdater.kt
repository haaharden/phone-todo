package com.self.phonetodo.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.self.phonetodo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseUpdateInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val htmlUrl: String,
    val isNewer: Boolean,
)

/**
 * In-app self-updater backed by GitHub Releases API.
 * Checks /releases/latest, streams the signed APK with progress, and launches Android Package Installer.
 */
object AppUpdater {

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkLatestRelease(repoSlug: String): Result<ReleaseUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanSlug = repoSlug.trim().removePrefix("https://github.com/").trim('/')
            require(cleanSlug.contains("/")) { "仓库格式应为 owner/repo，例如 haaharden/phone-todo" }

            val req = Request.Builder()
                .url("https://api.github.com/repos/$cleanSlug/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "PhoneToDo/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 404) error("暂未在 $cleanSlug 发现已发布的 Release")
                if (!resp.isSuccessful) error("检查更新失败 (HTTP ${resp.code})")

                val obj = json.parseToJsonElement(body).jsonObject
                val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val version = tag.removePrefix("v").removePrefix("V")
                val title = obj["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { tag } ?: tag
                val notes = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()

                val assets = obj["assets"]?.jsonArray.orEmpty()
                val apkAsset = assets
                    .mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                    .firstOrNull {
                        it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true
                    }

                val downloadUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull.orEmpty()
                val sizeBytes = apkAsset?.get("size")?.jsonPrimitive?.longOrNull ?: 0L

                ReleaseUpdateInfo(
                    tagName = tag,
                    versionName = version,
                    title = title,
                    releaseNotes = notes,
                    apkDownloadUrl = downloadUrl,
                    apkSizeBytes = sizeBytes,
                    htmlUrl = htmlUrl,
                    isNewer = isRemoteVersionNewer(BuildConfig.VERSION_NAME, version),
                )
            }
        }
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(downloadUrl.isNotBlank()) { "该 Release 未附带 APK 安装包" }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val outFile = File(dir, "PhoneToDo-update.apk")
            if (outFile.exists()) outFile.delete()

            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "PhoneToDo/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败 (HTTP ${resp.code})")
                val body = resp.body ?: error("响应内容为空")
                val total = body.contentLength()
                var downloaded = 0L
                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress((downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            onProgress(1f)
            outFile
        }
    }

    fun installApk(context: Context, apkFile: File): Result<Boolean> = runCatching {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return@runCatching false // needs user to allow unknown sources first
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    }

    fun hasInstallableApk(info: ReleaseUpdateInfo): Boolean =
        info.isNewer && info.apkDownloadUrl.isNotBlank()

    fun isRemoteVersionNewer(current: String, remote: String): Boolean {
        val cParts = current.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        val rParts = remote.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(cParts.size, rParts.size)
        for (i in 0 until maxLen) {
            val c = cParts.getOrElse(i) { 0 }
            val r = rParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
