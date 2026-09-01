# Phase 6 PDCA（产品化 UI 与设置 + 创作页）

## Plan

普通界面去除工程术语：Provider / JNI / ONNX / RTF / PSS / ABI / providerId / voiceId / referenceAudioPath / Native Runtime 等统一收进「设置 → 高级 → 开发与诊断」。设置页调整为：模型与服务 / 外观 / 音频 / 存储 / 隐私 / 高级 / 关于。外观支持跟随系统 / 亮色 / 暗色（Compose Material3 + DataStore 持久化）。模型与服务支持本地模型状态、云端（MiniMax）、系统语音与未来扩展。创作页只保留：当前音色、生成方式（本地生成 / 云端高清 / 系统语音）、文本输入、语速、生成、播放、保存、分享。

影响面：SettingsStore 增加主题/自动保存持久化；MainViewModel 增加主题、存储统计、生成方式选择与按 Provider 路由；ShineVoiceRoot 主题包裹与创作页改造；设置页重写。DB 不变；Provider 边界不变。

## Do

- DataStore：`ThemeMode`（SYSTEM/LIGHT/DARK）与 autoSave 持久化。
- ViewModel：`themeMode`/`storageStats`/`selectedProviderId` 状态；`newRequest` 按生成方式路由到 ZipVoice / MiniMax / Android System TTS；`providerLabel()` 输出 本地生成/云端高清/系统语音；生成前置校验按 Provider 区分（本地校验模型+参考，云端校验配置+voice_id）。
- UI 主题：`MaterialTheme(colorScheme = light/dark)`，跟随系统用 `isSystemInDarkTheme()`。
- 设置：分组标题 + 各区块卡片；稳定性测试、模型目录/校验/ABI、Provider 状态、参考音频路径等全部移到「高级 → 开发与诊断」。
- 创作页：当前音色卡片（底部弹层切换）、生成方式分段按钮、文本、语速滑杆、参考文本（本地模式）、生成/播放/保存/分享；结果卡显示所选生成方式并可分享。
- 音色页：参考音频绝对路径改为“已就绪/缺失”友好状态。

## Check

- CI：`:app:testDebugUnitTest` + `:app:assembleDebug`（见对应 commit run）。
- 主题三态切换与持久化、生成方式切换（本地↔系统↔云端）、分享、存储统计：涉及 Android UI 与 DataStore，**待真机验收**。
- 页面不再出现“Provider/RTF/PSS/ABI/providerId/…/Native Runtime”等工程术语（代码审查 + 真机走查）。

## Act

- CI 报错即修复重推。
- 本轮收束：创作页/设置页的 `SegmentedButton`（该 material3 BOM 版本不可用）改为跨版本稳定的 `FilterChip`；分享回调内无效的 `return@let` 改为 if/else 结构；补充 `OutlinedButton` 导入。
- 最终 CI（commit `535b044`）：`:app:testDebugUnitTest` 14 tests passed + `:app:assembleDebug` BUILD SUCCESSFUL；页面工程术语检查通过代码审查。
- 待真机验收项：亮/暗/跟随系统三态切换生效且重启后保持；三种生成方式连续切换 20 次不 Crash；云端分享音频；创作页各按钮在三种模式下的可用性。