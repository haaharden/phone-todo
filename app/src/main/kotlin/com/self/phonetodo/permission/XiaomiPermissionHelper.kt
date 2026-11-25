package com.self.phonetodo.permission

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionCheckItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val granted: Boolean?, // null = cannot programmatically verify (Xiaomi manual setting)
    val requiredFor: String,
    val isXiaomiSpecific: Boolean = false,
)

object XiaomiPermissionHelper {

    fun isXiaomiDevice(): Boolean {
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        return brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco") ||
            manufacturer.contains("xiaomi")
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    fun hasExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    @Suppress("DEPRECATION")
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasFullScreenIntentPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.canUseFullScreenIntent()
    }

    fun getCheckList(context: Context): List<PermissionCheckItem> {
        val list = mutableListOf(
            PermissionCheckItem(
                id = "notification",
                title = "通知权限",
                subtitle = "显示待办准点提醒与强提醒通知",
                granted = hasNotificationPermission(context),
                requiredFor = "核心提醒",
            ),
            PermissionCheckItem(
                id = "exact_alarm",
                title = "精准闹钟权限",
                subtitle = "允许息屏休眠时准点触发待办提醒",
                granted = hasExactAlarmPermission(context),
                requiredFor = "绝不漏发通知",
            ),
            PermissionCheckItem(
                id = "overlay",
                title = "显示悬浮窗",
                subtitle = "每次解锁手机时在顶部弹出极简用机统计胶囊",
                granted = hasOverlayPermission(context),
                requiredFor = "解锁顶部胶囊",
            ),
            PermissionCheckItem(
                id = "usage_stats",
                title = "使用情况访问权限",
                subtitle = "统计各 App 今日使用时长排行",
                granted = hasUsageStatsPermission(context),
                requiredFor = "用机时长统计",
            ),
            PermissionCheckItem(
                id = "battery",
                title = "省电策略：无限制",
                subtitle = if (isXiaomiDevice()) "防止系统在后台冻结服务，建议同时在多任务下拉加锁" else "防止系统在后台冻结服务与计时",
                granted = isIgnoringBatteryOptimizations(context),
                requiredFor = "全天后台运行",
            ),
            PermissionCheckItem(
                id = "fullscreen",
                title = "锁屏强提醒（全屏通知）",
                subtitle = "重要待办到点时在锁屏界面直接亮屏提醒",
                granted = hasFullScreenIntentPermission(context),
                requiredFor = "重要待办强提醒",
            ),
        )
        if (isXiaomiDevice()) {
            list += PermissionCheckItem(
                id = "xiaomi_autostart",
                title = "小米自启动",
                subtitle = "系统私有项无法自动检测；已开启即生效，允许开机与后台拉起",
                granted = null,
                requiredFor = "小米 13 Ultra 专项保活",
                isXiaomiSpecific = true,
            )
            list += PermissionCheckItem(
                id = "xiaomi_bg_popup",
                title = "小米后台弹出界面 & 锁屏显示",
                subtitle = "系统私有项无法自动检测；在「其他权限」中勾选即可",
                granted = null,
                requiredFor = "小米 13 Ultra 弹窗必备",
                isXiaomiSpecific = true,
            )
        }
        return list
    }

    fun openPermissionPage(context: Context, id: String) {
        val launched = when (id) {
            "notification" -> startSafe(
                context,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                },
            )
            "exact_alarm" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startSafe(
                    context,
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                )
            } else {
                true
            }
            "overlay" -> startSafe(
                context,
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
            "usage_stats" -> startSafe(
                context,
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
            ) || startSafe(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            "battery" -> if (isXiaomiDevice()) {
                openXiaomiBatterySaver(context) || startSafe(
                    context,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                )
            } else {
                startSafe(
                    context,
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                )
            }
            "fullscreen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startSafe(
                    context,
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                )
            } else {
                openAppDetails(context)
            }
            "xiaomi_autostart" -> openXiaomiAutoStart(context)
            "xiaomi_bg_popup" -> openXiaomiPermEditor(context)
            "xiaomi_battery_policy" -> openXiaomiBatterySaver(context)
            else -> false
        }
        if (!launched) {
            openAppDetails(context)
        }
    }

    private fun openXiaomiAutoStart(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
        )
        return candidates.any { startSafe(context, it) }
    }

    private fun openXiaomiPermEditor(context: Context): Boolean {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                putExtra("extra_pkgname", context.packageName)
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", context.packageName)
            },
        )
        return candidates.any { startSafe(context, it) }
    }

    private fun openXiaomiBatterySaver(context: Context): Boolean {
        val intent = Intent().apply {
            setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                ),
            )
            putExtra("package_name", context.packageName)
            putExtra("package_label", "Phone ToDo")
        }
        return startSafe(context, intent)
    }

    private fun openAppDetails(context: Context): Boolean = startSafe(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )

    private fun startSafe(context: Context, intent: Intent): Boolean {
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
