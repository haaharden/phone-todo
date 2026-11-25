package com.self.phonetodo.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED,
}

@Serializable
data class AppSettings(
    val version: Int = 2,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultTimeZone: String = "Asia/Shanghai",
    val showDone: Boolean = false,
    // Persistent foreground service
    val keepAliveServiceEnabled: Boolean = true,
    // Top unlock banner settings (default cooldown 0s so every unlock triggers reliably)
    val unlockBannerEnabled: Boolean = true,
    val unlockBannerCooldownSeconds: Int = 0,
    val unlockBannerAutoDismissSeconds: Int = 5,
    val unlockBannerOpacity: Float = 0.96f,
    // Quiet hours for unlock banner
    val quietHoursEnabled: Boolean = false,
    val quietStartMinutes: Int = 23 * 60 + 30,
    val quietEndMinutes: Int = 7 * 60,
    // Digital awareness soft thresholds
    val dailyUnlockWarningThreshold: Int = 40,
    val dailyScreenMinutesWarningThreshold: Int = 180,
    // GitHub Releases self-update repository ("owner/repo")
    val githubUpdateRepo: String = "haaharden/phone-todo",
    // GitHub private repository sync settings ("owner/repo")
    val githubSyncEnabled: Boolean = false,
    val githubSyncRepo: String = "",
    val lastSyncAt: Long? = null,
    val lastSyncStatus: String = "未配置同步",
)
