# Phase 1 实际测试证据

日期：2026-08-23（Asia/Shanghai）

## 测试环境

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 设备：`emulator-5554` / `sdk_gphone16k_x86_64` / Medium_Phone(AVD)
- Android：17 / API 37；ABI：`x86_64`（设备同时列出 `x86_64,arm64-v8a`）
- 分辨率：1080×2400，density 420
- 真机：当前 adb 未发现可用真机，因此在 ABI 匹配的 x86_64 模拟器完成验证
- Native：官方 sherpa-onnx v1.13.6 AAR；模型为官方 ZipVoice-Distill INT8 中文/英文 Emilia 包与 `vocos_24khz.onnx`

## 模型和真实生成

模型、vocoder、`tokens.txt`、`lexicon.txt`、`espeak-ng-data` 和官方 `test_wavs/leijun-1.wav` 已推送到应用外部文件目录。应用启动后显示“模型与参考音频已就绪（SHA-256 已校验）”，并成功初始化官方 `OfflineTts` ZipVoice 配置。

首次真实生成：

- 输入：中文 targetText；referenceText 为官方 `leijun-1.wav` 匹配文本
- Native 耗时：1,274 ms
- 音频：4,885 ms，24,000 Hz，单声道，PCM 16-bit
- 应用私有输出：`files/generated/5c51fee0-3e4d-455e-bbc1-27a2c22546d2.wav`
- WAV 校验：RIFF/WAVE，data 234,496 bytes，文件 234,540 bytes

播放和保存：

- 通过页面“播放”成功调用 MediaPlayer
- 通过系统 SAF “保存 WAV”成功写入 `/sdcard/Download/shinevoice-1787498235621.wav`

## 断网、生命周期和稳定性

- 断网：执行 `svc wifi disable`、`svc data disable`，确认 Wi-Fi=0；再次真实生成成功，耗时 1,103 ms。
- Home/返回：PID 保持 `6757`，页面恢复正常。
- 强制停止/重启：PID `6757 → 7307`；重启日志包含 Provider 注册、应用启动和 ZipVoice Native Runtime 重新初始化。
- 20 次稳定性：20/20 成功；串行执行，全部写入生成目录和 Room 历史。

20 次统计：

- 平均耗时：989 ms；最大耗时：1,032 ms
- 平均 RTF：0.204；最大 RTF：0.220
- 成功率：100%
- PSS：592,919 → 601,949 KB；增量：9,030 KB
- 音频时长：大多数为 4,885 ms；其中个别输出因模型静音边界为 4,695 ms、4,614 ms，均为成功 WAV

## Crash、ANR、资源

- ShineVoice Logcat：无 `FATAL EXCEPTION`、`SIGSEGV` 或 Native 错误。
- Crash buffer：空。
- `data_app_anr`：无条目。
- 模拟器历史 `system_app_anr` 中存在一个无关的 `com.google.android.adservices.api` 记录（2026-08-21），不属于本应用。
- `AudioPlaybackController` 在 Activity 销毁时释放 MediaPlayer；强制停止后新进程重新加载 Native Runtime，未发现资源残留导致的崩溃或增长异常。

原始设备、Logcat、Crash、内存和已保存 WAV 证据位于被 Git 忽略的 `artifacts/phase1/`，模型、AAR 和构建产物同样未纳入提交。
