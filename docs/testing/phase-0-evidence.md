# Phase 0 Check Evidence

测试日期：2026-08-23（Asia/Shanghai）

- Host：Windows 11，JDK 17.0.19，Gradle 9.3.1，Android SDK 36/37.1。
- Target：`emulator-5554`，`sdk_gphone16k_x86_64`，Android 17 / API 37，ABI `x86_64`。
- Build：`:app:assembleDebug` 成功。
- Unit：`:app:testDebugUnitTest` 成功。
- Install/launch：`adb install -r` 成功；`com.shinevoice.debug/com.shinevoice.MainActivity` 成功恢复为 resumed activity。
- UI：adb UI tree 分别确认 `创作`、`音色`、`历史`、`设置` 四个底部入口可访问。
- Registry：启动日志出现 `Provider registered: zipvoice_local`。
- Crash：启动后 Logcat 未发现 `FATAL EXCEPTION`、`AndroidRuntime` 或 ShineVoice 进程崩溃。
- Expected limitation：模型尚未部署时，页面明确显示缺少文件并禁用生成按钮；这是保护路径，不是 Mock。

