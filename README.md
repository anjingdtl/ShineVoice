# ShineVoice

ShineVoice V0.1 是一个本地优先的 Android 中文 AI 语音工作台原型。本轮只收束 Phase 0（工程与统一 Provider Kernel）和 Phase 1（sherpa-onnx + ZipVoice-Distill INT8 本地闭环）。

## 本地构建

环境要求：JDK 17、Android SDK 36、Gradle Wrapper。首次准备官方 sherpa-onnx AAR：

```powershell
.scriptsetch-sherpa-onnx.ps1
.gradlew.bat :app:assembleDebug --console=plain
```

模型和参考音频不进 Git。准备官方 ZipVoice-Distill INT8 模型及示例参考音频：

```powershell
.scriptsprepare-zipvoice-runtime.ps1
.scriptspush-zipvoice-assets.ps1 -Serial emulator-5554
.gradlew.bat :app:installDebug --console=plain
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

设置页提供真实的连续 20 次生成测试入口。测试结果会记录到 Room，模型状态通过文件完整性检查显示；DataStore 已作为 Phase 0 设置持久化边界接入。

## 当前边界

Android System TTS、MiniMax、ASR、录音/导入音色管理和完整产品 UI 留到后续 Phase；本轮不以占位 Provider 冒充真实能力。API Key、模型、WAV、AAR、APK 和构建目录均被 Git 忽略。

## 文档

- [架构说明](docs/architecture/ARCHITECTURE.md)
- [模型部署](docs/model-deployment.md)
- [Phase 0/1 测试计划](docs/testing/phase-0-1-test-plan.md)
- [开发方案基线](docs/ShineVoice_V0.1_原型版本开发方案.md)

