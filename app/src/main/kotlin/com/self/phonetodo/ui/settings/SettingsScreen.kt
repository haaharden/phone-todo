package com.self.phonetodo.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.self.phonetodo.BuildConfig
import com.self.phonetodo.importer.TodoDataPort
import com.self.phonetodo.model.AppSettings
import com.self.phonetodo.model.ThemeMode
import com.self.phonetodo.model.TodoData
import com.self.phonetodo.permission.XiaomiPermissionHelper
import com.self.phonetodo.reminder.NotificationHelper
import com.self.phonetodo.ui.theme.JadeOk
import com.self.phonetodo.update.AppUpdater
import com.self.phonetodo.update.ReleaseUpdateInfo
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    todoData: TodoData,
    hasSavedGithubToken: Boolean,
    isMonitorRunning: Boolean,
    latestRelease: ReleaseUpdateInfo?,
    onReleaseChecked: (ReleaseUpdateInfo?) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onToggleKeepAlive: (Boolean) -> Unit,
    onPreviewUnlockBanner: () -> Unit,
    onGithubConnectAndSync: suspend (repo: String, token: String) -> Result<String>,
    onGithubSyncNow: suspend () -> Result<String>,
    onGithubDisconnect: () -> Unit,
    onOpenImport: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val payload = TodoDataPort.exportJson(todoData)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选文件")
            }.onSuccess {
                Toast.makeText(context, "已导出 JSON 备份（${todoData.activeTodos().size} 条待办）", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, "导出失败：${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val payload = TodoDataPort.exportCsv(todoData)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选文件")
            }.onSuccess {
                Toast.makeText(context, "已导出 CSV 表格（${todoData.activeTodos().size} 条待办）", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, "导出失败：${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    var checkList by remember { mutableStateOf(XiaomiPermissionHelper.getCheckList(context)) }
    var syncRepoInput by remember(settings.githubSyncRepo) { mutableStateOf(settings.githubSyncRepo) }
    var syncTokenInput by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var tokenSaved by remember(hasSavedGithubToken) { mutableStateOf(hasSavedGithubToken) }
    var repoInput by remember(settings.githubUpdateRepo) { mutableStateOf(settings.githubUpdateRepo) }
    var syncBusy by remember { mutableStateOf(false) }

    var updateChecking by remember { mutableStateOf(false) }
    var updateDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var updateStatusText by remember(latestRelease) {
        mutableStateOf(
            when {
                latestRelease == null -> "当前版本 v${BuildConfig.VERSION_NAME}"
                latestRelease.isNewer -> "发现新版本 ${latestRelease.tagName}"
                else -> "已是最新版本 v${BuildConfig.VERSION_NAME}"
            },
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkList = XiaomiPermissionHelper.getCheckList(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // 1. Xiaomi HyperOS Keep-alive & Permissions
            SettingsGroup(
                title = if (XiaomiPermissionHelper.isXiaomiDevice()) {
                    "小米 13 Ultra 保活与权限"
                } else {
                    "后台保活与权限"
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isMonitorRunning) JadeOk else MaterialTheme.colorScheme.secondary),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isMonitorRunning) "静默后台解锁监听中" else "后台解锁监听已关闭（闹钟提醒仍正常）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "无通知栏常驻卡片；后台静默统计解锁次数与弹出顶部胶囊",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.keepAliveServiceEnabled,
                        onCheckedChange = onToggleKeepAlive,
                        colors = sleekSwitchColors(),
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

                checkList.forEachIndexed { idx, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val icon = when (item.granted) {
                            true -> Icons.Rounded.CheckCircle
                            false -> Icons.Rounded.Warning
                            null -> Icons.Rounded.Info
                        }
                        val tint = when (item.granted) {
                            true -> JadeOk
                            false -> MaterialTheme.colorScheme.secondary
                            null -> MaterialTheme.colorScheme.primary
                        }
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                item.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { XiaomiPermissionHelper.openPermissionPage(context, item.id) },
                        ) {
                            Text(
                                when (item.granted) {
                                    true -> "已开启"
                                    false -> "去开启"
                                    null -> "去检查"
                                },
                            )
                        }
                    }
                    if (idx < checkList.lastIndex) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { NotificationHelper.sendTestNotification(context, strong = false) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("测试通知")
                    }
                    OutlinedButton(
                        onClick = { NotificationHelper.sendTestNotification(context, strong = true) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("测试强提醒")
                    }
                }
            }

            // 2. Top Unlock Banner settings
            SettingsGroup(title = "解锁顶部悬浮胶囊") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("每次打开手机弹出顶部胶囊", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "仅显示今日打开手机次数与已用时长，轻触可查看详细统计",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.unlockBannerEnabled,
                        onCheckedChange = { enabled ->
                            onUpdateSettings { it.copy(unlockBannerEnabled = enabled) }
                        },
                        colors = sleekSwitchColors(),
                    )
                }

                Button(
                    onClick = onPreviewUnlockBanner,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("立即预览顶部胶囊")
                }

                Text(
                    text = if (settings.unlockBannerCooldownSeconds == 0) {
                        "弹出冷却：0 秒（每次解锁立即弹出）"
                    } else {
                        "弹出冷却：${settings.unlockBannerCooldownSeconds} 秒"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = settings.unlockBannerCooldownSeconds.toFloat(),
                    onValueChange = { v ->
                        onUpdateSettings { it.copy(unlockBannerCooldownSeconds = (v / 10f).roundToInt() * 10) }
                    },
                    valueRange = 0f..180f,
                )

                Text(
                    text = "自动收起：${settings.unlockBannerAutoDismissSeconds} 秒",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = settings.unlockBannerAutoDismissSeconds.toFloat(),
                    onValueChange = { v ->
                        onUpdateSettings { it.copy(unlockBannerAutoDismissSeconds = v.roundToInt()) }
                    },
                    valueRange = 3f..12f,
                )

                Text(
                    text = "每日解锁警示阈值：${settings.dailyUnlockWarningThreshold} 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.dailyUnlockWarningThreshold.toFloat(),
                    onValueChange = { v ->
                        onUpdateSettings { it.copy(dailyUnlockWarningThreshold = (v / 5f).roundToInt() * 5) }
                    },
                    valueRange = 15f..120f,
                )
            }

            // 3. In-app GitHub Releases Self-Updater
            SettingsGroup(title = "应用更新（GitHub Releases）") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = updateStatusText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (latestRelease?.isNewer == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = "本地版本 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (!updateChecking) {
                                updateChecking = true
                                updateStatusText = "正在检查 GitHub 最新版本…"
                                scope.launch {
                                    AppUpdater.checkLatestRelease(repoInput)
                                        .onSuccess { info ->
                                            onReleaseChecked(info)
                                            updateStatusText = if (info.isNewer) {
                                                "发现新版本 ${info.tagName}"
                                            } else {
                                                "当前已是最新版 (${info.tagName})"
                                            }
                                        }
                                        .onFailure { err ->
                                            updateStatusText = err.message ?: "检查更新失败"
                                        }
                                    updateChecking = false
                                }
                            }
                        },
                        enabled = !updateChecking && !updateDownloading,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (updateChecking) "检查中…" else "检查更新")
                    }
                }

                OutlinedTextField(
                    value = repoInput,
                    onValueChange = {
                        repoInput = it
                        onUpdateSettings { s -> s.copy(githubUpdateRepo = it.trim()) }
                    },
                    label = { Text("GitHub 仓库 (owner/repo)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                latestRelease?.takeIf { it.isNewer && it.apkDownloadUrl.isNotBlank() }?.let { rel ->
                    if (rel.releaseNotes.isNotBlank()) {
                        Text(
                            text = rel.releaseNotes.take(240),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (updateDownloading) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                        Text(
                            text = "正在下载安装包 ${(downloadProgress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        val sizeMb = if (rel.apkSizeBytes > 0) {
                            " (%.1f MB)".format(rel.apkSizeBytes / 1024f / 1024f)
                        } else {
                            ""
                        }
                        Button(
                            onClick = {
                                updateDownloading = true
                                downloadProgress = 0f
                                scope.launch {
                                    AppUpdater.downloadApk(context, rel.apkDownloadUrl) { p ->
                                        downloadProgress = p
                                    }.onSuccess { apkFile ->
                                        AppUpdater.installApk(context, apkFile)
                                            .onSuccess { launched ->
                                                if (!launched) {
                                                    Toast.makeText(
                                                        context,
                                                        "请先允许 Phone ToDo 安装未知来源应用，返回后再点一次安装",
                                                        Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                            }
                                            .onFailure { err ->
                                                Toast.makeText(context, err.message ?: "启动安装失败", Toast.LENGTH_LONG).show()
                                            }
                                    }.onFailure { err ->
                                        Toast.makeText(context, err.message ?: "下载失败", Toast.LENGTH_LONG).show()
                                    }
                                    updateDownloading = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("下载并更新到 ${rel.tagName}$sizeMb")
                        }
                    }
                }
            }

            // 4. GitHub Private Repo Sync with DeskTodo
            SettingsGroup(title = "GitHub 私有仓库同步（DeskTodo 互通）") {
                Text(
                    text = settings.lastSyncStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "在 GitHub 建一个私有仓库并生成 Fine-grained Token（仅勾选该仓库 Contents 读写权限），手机和电脑填同一套即可自动双向同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = syncRepoInput,
                    onValueChange = { syncRepoInput = it },
                    label = { Text("私有仓库 (owner/repo)") },
                    placeholder = { Text("haaharden/todo-sync") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = syncTokenInput,
                    onValueChange = { syncTokenInput = it },
                    label = { Text("GitHub Personal Access Token") },
                    placeholder = {
                        Text(if (tokenSaved) "已加密保存（留空则沿用当前 Token）" else "github_pat_... 或 ghp_...")
                    },
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                imageVector = if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (tokenVisible) "隐藏 Token" else "显示 Token",
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                val configModified = syncRepoInput.trim() != settings.githubSyncRepo || syncTokenInput.isNotBlank()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!settings.githubSyncEnabled || configModified) {
                        Button(
                            onClick = {
                                if (!syncBusy) {
                                    syncBusy = true
                                    scope.launch {
                                        val res = onGithubConnectAndSync(syncRepoInput, syncTokenInput)
                                        res.onSuccess { msg ->
                                            tokenSaved = true
                                            syncTokenInput = ""
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            Toast.makeText(context, err.message ?: "连接失败", Toast.LENGTH_LONG).show()
                                        }
                                        syncBusy = false
                                    }
                                }
                            },
                            enabled = !syncBusy && syncRepoInput.isNotBlank() && (syncTokenInput.isNotBlank() || tokenSaved),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (syncBusy) "验证并同步中…" else "验证并启用同步")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (!syncBusy) {
                                    syncBusy = true
                                    scope.launch {
                                        val res = onGithubSyncNow()
                                        val msg = res.getOrElse { it.message ?: "同步失败" }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        syncBusy = false
                                    }
                                }
                            },
                            enabled = !syncBusy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (syncBusy) "同步中…" else "立即同步")
                        }
                    }

                    if (settings.githubSyncEnabled || tokenSaved) {
                        OutlinedButton(
                            onClick = {
                                onGithubDisconnect()
                                tokenSaved = false
                                syncTokenInput = ""
                                Toast.makeText(context, "已断开 GitHub 同步", Toast.LENGTH_SHORT).show()
                            },
                            enabled = !syncBusy,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("断开")
                        }
                    }
                }
            }

            // 5. Universal Data Import & Export (JSON / CSV / Markdown)
            SettingsGroup(title = "数据导入与导出（通用格式）") {
                Text(
                    text = "支持标准 JSON 完整备份、CSV 表格以及 Markdown / 纯文本清单，导出文件可直接在表格或电脑端通用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { exportJsonLauncher.launch("todos-${LocalDate.now()}.json") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("导出 JSON")
                    }
                    OutlinedButton(
                        onClick = { exportCsvLauncher.launch("todos-${LocalDate.now()}.csv") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("导出 CSV")
                    }
                }

                Button(
                    onClick = onOpenImport,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导入数据（JSON / CSV / 文本）")
                }
            }

            // 6. Theme
            SettingsGroup(title = "外观") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val modes = listOf(
                        ThemeMode.SYSTEM to "跟随系统",
                        ThemeMode.LIGHT to "矿石纸白",
                        ThemeMode.DARK to "曜石暗色",
                        ThemeMode.AMOLED to "纯黑 OLED",
                    )
                    modes.forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                            label = { Text(label) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun sleekSwitchColors(): SwitchColors {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = if (isDark) Color(0xFFDCE4E2) else Color.White,
        uncheckedTrackColor = if (isDark) Color(0xFF2D3740) else Color(0xFFD9E0DC),
        uncheckedBorderColor = if (isDark) Color(0xFF53626E) else Color(0xFFB6C1BC),
    )
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}
