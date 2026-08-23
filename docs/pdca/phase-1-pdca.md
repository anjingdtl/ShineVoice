# Phase 1 PDCA

## Plan

以官方 sherpa-onnx v1.13.6 Kotlin JNI API 接入 ZipVoice-Distill INT8；用真实 `reference.wav + referenceText + 中文 targetText` 在 Android CPU 上生成并保存 WAV，完成断网、重启、生命周期与连续 20 次稳定性验收。

## Do

使用 `OfflineTtsConfig.zipvoice` 配置 encoder/decoder/vocoder/dataDir/lexicon/tokens，通过 `GenerationConfig` 传入参考音频样本、采样率、参考文本和 4 steps。单一 Native Runtime 复用，TtsManager 串行化任务，Room 保存每次结果。

## Check

2026-08-23，使用 Debug APK 在 `emulator-5554`（`sdk_gphone16k_x86_64`，Android 17/API 37，x86_64）完成实际验收。当前环境未发现可用真机；官方 AAR 同时包含 `x86_64` 与 `arm64-v8a`，因此本模拟器 ABI 可直接验证。

- [x] 真实 Android 生成成功并输出 WAV：官方 `reference.wav` + 匹配 `referenceText` + 中文 `targetText`，生成 24 kHz/16-bit/mono WAV。
- [x] 播放成功：Activity-scoped `MediaPlayer` 播放生成文件。
- [x] SAF 保存成功：保存到 `/sdcard/Download/shinevoice-1787498235621.wav`，234,540 bytes。
- [x] 断网生成成功：Wi-Fi=0、移动数据关闭时再次生成成功，耗时 1,103 ms。
- [x] APP 重启后生成成功：强制停止后重新启动，PID 变化且 Native Runtime 重新初始化。
- [x] 前后台/Activity 生命周期无 Crash，`MainActivity.onDestroy()` 释放播放资源；进程重启后 Native Runtime 可重新加载。
- [x] 连续 20 次成功率 100%，无 ShineVoice Crash/ANR/Native 错误。
- [x] 性能记录：平均 989 ms，最大 1,032 ms，平均 RTF 0.204，最大 RTF 0.220，PSS 592,919 → 601,949 KB，增量 9,030 KB。
- [x] Logcat 与 crash buffer 清洁；`data_app_anr` 无条目。模拟器保留的历史 `system_app_anr` 属于无关的 Google AdServices 进程。

详细记录见 [`docs/testing/phase-1-evidence.md`](../testing/phase-1-evidence.md)。

## Act

本轮未发现需要返修的 Native 配置、ABI、路径、音频文件、生命周期、内存或稳定性问题。保留官方第三方库边界；Android System TTS、MiniMax、ASR 和完整音色管理不在本 Phase 1 范围内。
