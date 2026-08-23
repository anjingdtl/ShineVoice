# Phase 0 PDCA

## Plan

建立可构建、可安装、可启动的 ShineVoice Android 工程；完成 Compose 四页入口、Room、DataStore、统一 `TtsProvider/ProviderRegistry/TtsManager`，保留 Native Provider 接入边界。

## Do

使用 Kotlin、Compose、Material 3、AndroidX、Room、DataStore 和 Gradle Wrapper 建立单模块工程。Provider Registry 只注册真实的本地 ZipVoice Provider；模型和 AAR 使用脚本获取，不进入 Git。

## Check

检查结果（2026-08-23，`emulator-5554`，API 37 / x86_64）：

- [x] Gradle 配置与 `assembleDebug`
- [x] Debug APK 生成：`app/build/outputs/apk/debug/app-debug.apk`
- [x] APK 安装并启动：`com.shinevoice.debug/com.shinevoice.MainActivity`
- [x] 创作、音色、历史、设置四页可访问（adb UI tree 确认）
- [x] `ProviderRegistryTest` / `testDebugUnitTest` 通过
- [x] 启动 Logcat 无 `FATAL EXCEPTION` / `AndroidRuntime` / ShineVoice 崩溃

## Act

只修复 Phase 0 构建、安装、启动、Registry 或资源释放问题；不扩展 MiniMax、System TTS 或精装修 UI。
