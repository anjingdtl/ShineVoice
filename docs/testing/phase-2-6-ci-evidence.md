# Phase 2–6 远端 CI 验收证据

日期：2026-09-01（Asia/Shanghai）

本文件只记录**可被远端 CI 客观验证**的内容。所有依赖 Android 真机的行为
（TTS 实发音、真实网络、Keystore 写入、主题切换视觉、MediaPlayer 播放等）
一律不在本文件中声称通过，统一标记为「待真机验收」。

## 环境

- 仓库：`anjingdtl/ShineVoice`，分支 `main`
- Runner：`ubuntu-latest`，JDK 17（temurin），`./gradlew`（POSIX 包装器）
- 前置步骤：`scripts/fetch-sherpa-onnx.sh` 拉取官方 sherpa-onnx 1.13.6 AAR 并校验 SHA-256（用例无关，无真实模型不参与单元测试）

## CI 流程（.github/workflows/ci.yml）

1. `./gradlew :app:testDebugUnitTest --no-daemon`
2. `./gradlew :app:assembleDebug --no-daemon`
3. 上传 Debug APK 为 workflow artifact

## 结果（commit 535b044，run 33501179464）

| 检查项 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 14 tests passed（PcmAudio / MiniMaxApiClient / ProviderRegistry 等） |
| `:app:assembleDebug` | BUILD SUCCESSFUL，产出 `app-debug.apk` |
| AndroidSystemTtsProvider 编译 | 通过（含初始化回调、utterance 监听、Bundle 参数） |
| MiniMaxProvider / MiniMaxApiClient 编译 | 通过（JSON 契约单测全绿） |
| ShineVoiceRoot（主题/设置/创作页）编译 | 通过 |
| ZipVoice Native 链路 | AAR 下载校验一致；本地编译/打包未回归 |

## 本轮 Check 修复记录（3 次迭代）

| Commit | 内容 | CI 结果 |
| --- | --- | --- |
| `1735ce9` | 修复 `client` 作用域、`Result.Success/Failure`、`parseSynthesisAudio`、`continue in inline lambda`、`OutlinedButton` 导入等 11 处编译错误 | 4 处残留（Engine 常量、return@let、SegmentedButton） |
| `f1ec8a3` | `"pitch"/"rate"` Bundle 键替代 SDK 常量、分享回调去 `return@let`、`SegmentedButton`→`FilterChip` | 编译全过，1 个单测失败（WAV 解码端序） |
| `535b044` | `MonoWavReader` 小端读取 header（RIFF/fmt/data） | ✅ 全绿 |

## 待真机验收（不在本文件声称完成）

- Android System TTS：中文 Voice 选择、speed/pitch 实际生效、`synthesizeToFile` 文件可播放、shutdown 无泄漏
- MiniMax：真实连接测试 / 克隆 / TTS / 错误 Key / 断网 / 超时；API Key 经 Android Keystore 加密写入
- 主题三态（跟随系统/亮/暗）切换视觉与 DataStore 持久化
- 音色库录音导入、三 Provider 绑定、试听
- 历史批量 ZIP 导出 / 分享在真机上完成
- 创作页三种生成方式连续切换 20 次不 Crash