# ShineVoice

ShineVoice V0.1 是一个本地优先、云端增强、Provider 可扩展的 Android 中文 AI 语音工作台原型。本轮收束 Phase 0（工程与统一 Provider Kernel）、Phase 1（sherpa-onnx + ZipVoice-Distill INT8 本地闭环），并完成 Phase 1.1 收束：模型状态与参考音频解耦、GitHub Actions CI。

## 本地构建

环境要求：JDK 17、Android SDK 36、Gradle Wrapper。首次准备官方 sherpa-onnx AAR：

```powershell
.\scripts\fetch-sherpa-onnx.ps1
.\gradlew.bat :app:assembleDebug --console=plain
```

Linux / macOS 使用 `./gradlew` 获得等价命令：

```bash
bash scripts/fetch-sherpa-onnx.sh
./gradlew :app:assembleDebug --console=plain
```

模型和参考音频不进 Git。准备官方 ZipVoice-Distill INT8 模型及示例参考音频：

```powershell
.\scripts\prepare-zipvoice-runtime.ps1
.\scripts\push-zipvoice-assets.ps1 -Serial emulator-5554
.\gradlew.bat :app:installDebug --console=plain
```

应用会从 Android app-specific external files 目录读取：

```text
Android/data/com.shinevoice.debug/files/
├── models/zipvoice/
│   ├── sherpa-onnx-zipvoice-distill-int8-zh-en-emilia/
│   └── vocos_24khz.onnx
└── voices/default/reference.wav
```

## 运行闭环

启动 ShineVoice 后，首页的 `targetText` 和 `referenceText` 会通过统一 `TtsManager` 路由到 `SherpaZipVoiceProvider`。Provider 使用官方 Kotlin JNI facade 的 `OfflineTts.generateWithConfig`，传入真实 `referenceAudio/referenceSampleRate/referenceText`，然后写入应用私有 `generated/*.wav`，可在页面播放并通过系统文件选择器保存。

模型状态（`ModelStatus`）只校验模型文件与完整性校验和；参考音频与 `referenceText` 由音色层（`VoiceProfile`）单独校验，二者互不耦合。设置页提供真实的连续 20 次生成测试入口。测试结果会记录到 Room；DataStore 已作为 Phase 0 设置持久化边界接入。

## 持续集成

仓库根目录的 `gradlew`（POSIX）与 `gradlew.bat`（Windows）等效。GitHub Actions 工作流 [`.github/workflows/ci.yml`](.github/workflows/ci.yml) 在每次 push/PR 时执行：

```text
scripts/fetch-sherpa-onnx.sh   # 拉取官方 AAR 并校验 SHA-256
:app:testDebugUnitTest         # JVM 单元测试
:app:assembleDebug             # Debug APK
```

本地等价验收命令：

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## 当前能力

- **本地生成**（ZipVoice-Distill INT8，离线可用）：文本 → 语音，支持语速调节。
- **音色库**：列表化管理音色；创建/重命名/删除；录音或导入（WAV/MP3/M4A/AAC → 24 kHz 单声道）；参考文本编辑；设为当前；试听；最近使用；本地 / 云端 / 系统绑定徽标。
- **系统语音**（Android System TTS）：设备自带中文引擎朗读，支持语速与音高。
- **云端高清**（MiniMax，BYOK）：API Key 经 Android Keystore 加密保存；测试连接；上传参考音频克隆云端音色并保存 voice_id；云端合成、播放、保存、分享。
- **生成历史**：按日期分组折叠，点击播放，迷你播放器，多选 / 全选 / 批量删除 / 批量分享 / ZIP 打包导出。
- **设置**：模型与服务 / 外观（跟随系统、亮色、暗色）/ 音频 / 存储 / 隐私 / 高级（开发与诊断）/ 关于。
- 工程术语（Provider、JNI、RTF、PSS、ABI、providerId、voiceId、referenceAudioPath 等）不进入普通界面，统一收纳在「设置 → 高级 → 开发与诊断」。

## 当前边界

ASR 自动识别参考文本、云端音色删除接口路由、模型下载器留到后续版本；云端与系统语音链路依赖真实设备/账号，验收见各 Phase PDCA 的“待真机验收”清单。API Key、模型、WAV、AAR、APK 和构建目录均被 Git 忽略。

## 文档

- [架构说明](docs/architecture/ARCHITECTURE.md)
- [模型部署](docs/model-deployment.md)
- [Phase 0/1 测试计划](docs/testing/phase-0-1-test-plan.md)
- [开发方案基线](docs/ShineVoice_V0.1_原型版本开发方案.md)
- [PDCA 记录](docs/pdca/)（phase-1-1 / 2 / 3 / 4 / 5 / 6）