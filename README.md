# Phone ToDo

专为 **小米 13 Ultra（HyperOS）** 打造的极简、强提醒原生 Android 待办软件，替代臃肿且漏通知的 Super Productivity，并与桌面端 `DeskTodo` 双向同步。

## 核心特性

- **克制、高辨识度的精密表盘与纸单视觉**：矿石纸白 / 曜石暗色 / 纯黑 OLED 三套主题，顶部表盘式今日数据条（等宽数字显示今日解锁次数、亮屏时长、待完成数），整页细线待办纸单与悬浮胶囊底栏。
- **准点必达提醒**：`AlarmManager.setAlarmClock()` 调度 + 重要待办锁屏亮屏响铃强提醒 + 开机自动恢复。
- **小米 HyperOS 双路解锁弹窗**：结合 `ACTION_USER_PRESENT` 与 `SCREEN_ON` 锁屏状态轮询，兼容小米屏下指纹直解；解锁后避让挖孔在顶部弹出黑曜石悬浮胶囊（显示今日解锁次数、已用时长与优先待办，支持直接勾选）；未授悬浮窗权限时自动降级为系统顶部横幅通知并提示一键授权。
- **双轨手机使用时间统计**：本地 SQLite 精准记录解锁次数与亮屏时长（跨午夜自动切分）+ `UsageStatsManager` 各 App 时长排行 + 24h 节律与 7 日趋势 + Glance 桌面小组件。
- **Super Productivity 批量导入**：按官方源码解析完整 JSON 备份（项目→分类、活跃/归档任务、提醒与重复规则）及 Worklog CSV，按原 ID 自动去重。
- **Google Drive 双向同步**：与 Windows `DeskTodo` 共用 `PhoneDeskTodo/sync-data.json`（仅 `drive.file` 最小权限，条目级 LWW + 软删除墓碑合并）。
- **应用内在线更新**：内置 GitHub Releases 自动检查、进度下载与覆盖安装（使用固定发布证书签名，升级不丢数据）。

---

## 一、首次安装小米 13 Ultra（HyperOS）保活清单

打开 **Phone ToDo → 右上角设置 → 小米 13 Ultra 保活与权限**：

1. 确认顶部绿点显示 **「常驻监听服务运行中」**
2. 依次开启：**通知权限**、**精准闹钟权限**、**显示悬浮窗**（顶部解锁横幅必备）、**使用情况访问权限**
3. 小米专属三项：
   - **小米自启动**：开启
   - **小米后台弹出界面 & 锁屏显示**：在「其他权限」中勾选
   - **省电策略**：选「无限制」，并在多任务界面下拉锁定本应用
4. 点击 **「立即预览顶部横幅」** 验证悬浮窗效果，然后锁屏解锁实测！

> 注意：如果你此前安装过 `app-debug.apk` 测试版，首次安装 GitHub Release 的正式签名 APK 前请先卸载旧测试版；之后所有 GitHub Release 均使用同一发布密钥签名，直接在 **设置 → 应用更新** 一键覆盖升级即可。

---

## 二、应用内更新（GitHub Releases）

1. 打开 **Phone ToDo → 设置 → 应用更新（GitHub Releases）**
2. 默认监听仓库：`haaharden/phone-todo`
3. 点 **「检查更新」**，有新版时点击 **「下载并更新」** 即可下载 APK 并拉起系统安装器

---

## 三、Google Drive 同步配置提示

- Android OAuth Client 所需信息：
  - 包名：`com.self.phonetodo`
  - 正式版签名 SHA-1：`D5:BF:0B:34:1D:AF:76:C5:DC:B7:36:20:CF:79:06:27:CB:5F:43:F2`
  - 回调 URI：`com.self.phonetodo://oauth2callback`
- 在 Google Cloud Console 将 OAuth 同意屏幕发布状态设为 **Production**（个人自用无需提交审核），可避免测试模式 7 天 Refresh Token 过期。

---

## 四、本地编译

```powershell
cd phone-todo
.\gradlew.bat testDebugUnitTest assembleRelease
```
