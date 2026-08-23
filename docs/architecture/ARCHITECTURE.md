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

