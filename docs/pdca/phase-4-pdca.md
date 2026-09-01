# Phase 4 PDCA（Android System TTS）

## Plan

实现 `AndroidSystemTtsProvider`：系统 TTS Engine、Voice 枚举、选择中文语音、speed、pitch、播放（synthesizeToFile 文件输出）、生命周期（initialize/release/shutdown）、错误转统一 `TtsError`。UI 标签“系统语音”。

影响面：新增 `provider/androidtts`；`TtsErrorCode` 增加 `SystemTtsError`；Application 注册新 Provider；不触碰 ZipVoice 链路。

## Do

- `AndroidSystemTtsProvider`：`TextToSpeech` 在主线程初始化（suspendCancellableCoroutine + Main dispatcher），选择中文 Locale（zh / cmn），`getVoices()` 枚举中文 Voice；`synthesize` 用 `synthesizeToFile`（引擎支持时）输出到 `generated/{taskId}.wav`，`UtteranceProgressListener.onDone` 回调完成；speed/pitch 以百分比传给引擎参数；`cancel`→stop，`release`→shutdown。
- 注册进 `ProviderRegistry`（displayName＝系统语音），生命周期由 ProviderRegistry 管理。
- 错误映射：初始化失败、开始失败、朗读失败统一为 `SystemTtsError` 中文提示。

## Check

- CI：`:app:testDebugUnitTest` + `:app:assembleDebug`（见对应 commit run）。
- 系统 TTS 依赖设备引擎（Google TTS 等），沙箱 CI 无 Android 环境，**待真机验收**：中文 Voice 选择、speechRate/pitch 生效、synthesizeToFile 文件可播放、Activity 重建后继续可用、shutdown 无泄漏、多引擎切换。

## Act

- CI 报错即修复重推。
- 本轮收束：修复 `TextToSpeech` 初始化回调中 `client` 作用域、`getVoices()` 错误判断、`KEY_PARAM_PITCH/KEY_PARAM_SPEED` 常量（改为稳定的 `"pitch"/"rate"` Bundle 键）、补充 `UtteranceProgressListener.onError(String)` 抽象实现。
- 最终 CI（`fix: MonoWavReader ...` 同轮，commit `535b044`）：`:app:testDebugUnitTest` 14 tests passed + `:app:assembleDebug` BUILD SUCCESSFUL，含本 Provider 编译与打包。
- 真机验收前不伪造完成记录；生成链路是否可用由创作页 Provider 切换（Phase 6/7）统一评估。