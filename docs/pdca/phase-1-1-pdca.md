# Phase 1.1 PDCA（收束）

> 2026-09-01 起在 `anjingdtl/ShineVoice` 远端 `main` 上直接施工，沙箱无 Android SDK，构建/单元测试由 GitHub Actions CI 验证，模拟器与真机能力按实标记。

## Plan

收束 Phase 1 遗留问题，为 Phase 2（音色库）等后续阶段建立可验证的 CI 基线：

1. 修正 README 中错误的 PowerShell 命令（`.scriptsprepare…` 缺反斜杠、缺 `.\scripts\` 前缀、缺 POSIX `gradlew`）。
2. 将 ZipVoice **模型状态** 与默认 `reference.wav` 解耦：`ModelStatus` 只检查模型；`VoiceProfile` 单独校验参考音频与 `referenceText`。
3. 增加 GitHub Actions：`:app:testDebugUnitTest` + `:app:assembleDebug`。
4. 保持现有 ZipVoice Native 链路不回归。

影响面评估：`ModelDirectoryResolver`/`SherpaZipVoiceProvider`/`MainViewModel`/创作页状态显示；无 DB schema 变更；sherpa-onnx 调用链不动。

## Do

- README：修正为 `.\scripts\fetch-sherpa-onnx.ps1` 等正确命令，新增 POSIX 等价命令与 CI 说明。
- 新增 `scripts/fetch-sherpa-onnx.sh`（pip 等价 PowerShell 版，含 SHA-256 校验）。
- 新增 `gradlew`（POSIX launcher，gradle 9.3.1 官方脚本）与 `.github/workflows/ci.yml`（JDK 17，先拉 AAR 再 `testDebugUnitTest` + `assembleDebug`，上传 Debug APK artifact）。
- `ZipVoiceModelLayout.missingFiles()` 移除 `reference.wav`；`ZipVoiceModelStatus` 移除 `referencePath`；新增 `ReferenceAudioStatus` 与 `ModelDirectoryResolver.referenceAudioStatus(referenceText)`。
- `SherpaZipVoiceProvider.validateConfig()` 只报模型状态；新增 `validateReference()` 校验参考音频存在与 `referenceText` 非空。
- `MainUiState` 增加 `referenceStatus`；生成与 20 次稳定性测试前置调用 `validateReference`；创作页生成按钮同时门控模型与音色状态。

## Check

- 本地沙箱无 Android SDK，无法本地构建；以 GitHub Actions 结果为唯一构建/单测验收依据。
- CI 结果见对应 commit 的 Actions run（`:app:testDebugUnitTest`、`:app:assembleDebug`、AAR SHA-256 校验、Debug APK artifact）。
- 真机/模拟器能力（真实生成、播放、断网、重启）仍以 Phase 1 已验收证据为准，本轮代码改动不改变 Native 调用链。

## Act

- 依 CI 结果修复后进入 Phase 2。
- 待真机验收项：解耦后的模型/音色状态在实际设备上重新检测一次；生成链路 20 次稳定性复跑。