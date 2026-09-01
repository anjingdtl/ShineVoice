# ShineVoice V0.1 架构

## Phase 0/1 运行链路

```text
Compose UI
    ↓
MainViewModel
    ↓
TtsManager（单任务 Mutex）
    ↓
ProviderRegistry
    ↓
SherpaZipVoiceProvider
    ↓
SherpaRuntimeManager（单个 OfflineTts 生命周期）
    ↓
sherpa-onnx AAR / JNI / ONNX Runtime
    ↓
generated/{taskId}.wav
```

UI 不创建 Provider，不直接访问 JNI；Provider 以统一 `TtsRequest/TtsResult/TtsCapabilities` 暴露能力。`TtsManager` 负责 Provider 定位、单任务串行、统一异常兜底和历史写入。

## Native 生命周期

`SherpaRuntimeManager` 只在模型与参考音频完整时创建 `OfflineTts`，并在同一进程复用它，避免每次生成重建全部 Native 对象。Activity 销毁只释放播放资源；Native Runtime 由 Application 级 Provider 持有，直到进程结束或显式 release。所有可预期 Kotlin/Native 初始化和生成异常都转换成 `TtsError`。

## 文件与数据

- Room：`VoiceProfileEntity`、`GenerationHistoryEntity`。
- DataStore：Phase 0 设置持久化边界。
- app-private files：生成 WAV。
- app-specific external files：模型、参考音频，便于 adb 部署且不进入 APK。
- Android FileProvider + SAF：播放应用私有 WAV，并把 WAV 保存到用户选择的位置。

## Provider 边界

当前只注册真实可用的 `SherpaZipVoiceProvider`。`TtsProvider` 和 `VoiceCloneProvider` 已按方案定义，后续 System TTS/MiniMax 只需新增实现并注册，不把厂商条件分支散到页面或 ViewModel。

## 模型状态与音色解耦

`ModelDirectoryResolver.inspect()` 产生 `ZipVoiceModelStatus`，只检查模型文件与完整性校验和；默认 `reference.wav` 与 `referenceText` 属于音色（VoiceProfile）输入，由 `ModelDirectoryResolver.referenceAudioStatus(referenceText)` 单独校验为 `ReferenceAudioStatus`。两种状态独立显示、独立门控：模型缺失不会误报“参考音频问题”，反之亦然。`SherpaZipVoiceProvider.validateReference()` 在生成本对指定音色的参考音频与参考文本做前置校验。

## 音色库（Phase 2）

`VoiceProfileEntity` 是用户视角的“音色”，可绑定 ZipVoice（`referenceAudioPath`/`referenceText`）、MiniMax（`minimaxVoiceId`）与 Android System TTS（`androidTtsEngine`/`androidTtsVoice`），并带 `isCurrent`/`isDefault`/`lastUsedAt` 便于最近使用与当前音色管理。`VoiceProfileManager` 是音色边界：负责 CRUD、设为当前、最近使用与删除时文件/DB 一致性（音色目录 `voices/{id}/` 随行删除；默认音色不可删）。

音频预处理统一走 `core/audio`：WAV 由 `MonoWavReader` 解析，MP3/M4A/AAC 由 `AudioCodecDecoder`（MediaExtractor + MediaCodec）解码，统一重采样为 24 kHz/16-bit/mono 的 `reference.wav`；录音由 `VoiceRecorder`（AudioRecord）产出可直接生成的标准参考音频。创作页的“当前音色”通过底部弹层快速切换，生成与稳定性测试都按当前音色的参考音频与参考文本执行。

## Provider 阵容（Phase 3~6）

```text
SherpaZipVoiceProvider    本地生成   离线 / 声音克隆（ZipVoice）
AndroidSystemTtsProvider  系统语音   设备 TTS 引擎，synthesizeToFile 文件输出
MiniMaxProvider           云端高清   BYOK + Keystore 加密，上传克隆 voice_id，t2a_v2 合成
```

创作页的“生成方式”只展示以上三个用户标签；`TtsRequest.providerId` 由 ViewModel 按所选方式路由，Provider 内部对象不暴露给普通 UI。设置页「高级 → 开发与诊断」集中展示 Provider 快照、模型目录、完整性校验、ABI、RTF/PSS 与 20 次稳定性测试等工程数据。历史页是生成文件库：日期分组、多选、批量删除/分享/导出 ZIP，不展示耗时/错误码等 Debug 字段。

