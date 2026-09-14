# ShineVoice Phase 9 Final Acceptance Evidence

验收日期：2026-09-14（Asia/Shanghai）

## Source

- 起始基线：`1c37bf3801080daea6fb7b6437027765f35ad8f8`，分支 `main`。
- `V1.0.0` tag 与既有 Release 未覆盖；本次正式版本为 `V1.0.1`，`versionCode=1000001`。
- 工作区原有 `.zcode/` 未纳入提交；模型、AAR、WAV、APK、临时测试目录均未纳入提交。
- 设备：`Medium_Phone` / `emulator-5554`，Android 17 / API 37，x86_64，16 KB page size，1080×2400，约 16 GB RAM。
- 构建：JDK 17，Gradle 9.3.1，Android SDK 36；官方 sherpa-onnx AAR 1.13.8，SHA-256 已校验。

## Unit tests

命令：`:app:testDebugUnitTest`。

- PASS：46 tests，0 failures，0 errors。
- 覆盖 MiniMax JSON/API 合同、语言映射、WAV 解析、PCM 速度缩放、Room 相关纯 JVM 逻辑、更新协议和 24 小时检查节流。

## Lint

命令：`:app:lintDebug`。

- PASS：0 Error/Fatal。
- 仅保留 2 个既有 Warning：备份规则提示、启动图 drawable-nodpi 形状提示。

## Debug build

- PASS：`:app:assembleDebug`。
- PASS：`:app:assembleDebugAndroidTest`。
- 最终 clean build 与后续启动顺序修复后的增量构建均通过。

## Room migration

- PASS：`RoomMigrationContractTest#migrationPreservesLegacyRowsAndAddsTaskParameters`，1/1。
- 证据：[room-migration-final-instrumentation.txt](../../artifacts/qa/phase-9/room-migration-final-instrumentation.txt)、[room-migration-final.logcat.txt](../../artifacts/qa/phase-9/room-migration-final.logcat.txt)。
- 结论：2→3 显式迁移保留旧记录；新增 `language` 可为空、`speed` 默认 1.0。

## ZipVoice real test

- PASS：中英真实推理矩阵，`test08_zipVoiceChineseAndEnglishRealInference`，1/1。
- 证据：[zipvoice-language-speed-clean-instrumentation.txt](../../artifacts/qa/phase-9/zipvoice-language-speed-clean-instrumentation.txt)、[zipvoice-language-speed-clean.logcat.txt](../../artifacts/qa/phase-9/zipvoice-language-speed-clean.logcat.txt)。
- 中文：0.5x / 1.0x / 2.0x 的 WAV 时长约 27.35s / 9.77s / 4.89s；English：约 40.27s / 14.47s / 6.73s；六次均 `WAV_HEADER_OK`，无 crash marker。
- PASS：20 次连续真实生成 20/20；PASS：50 次内存趋势测试 50/50、0 failures。证据：[zipvoice-stability-20.txt](../../artifacts/qa/phase-9/zipvoice-stability-20.txt)、[zipvoice-memory-50.txt](../../artifacts/qa/phase-9/zipvoice-memory-50.txt)。
- 2.0x 走应用层 PCM 后处理：原生请求固定使用安全的 1.0x，再按请求速度缩放；因此用户可见速度与时长通过，已规避上游 ZipVoice 快速原生路径的已知缓冲区问题。参考：[sherpa-onnx issue #3675](https://github.com/k2-fsa/sherpa-onnx/issues/3675)。

## System TTS real test

- PASS：Google System TTS 实际引擎、284 个音色，中英各 0.5x / 1.0x / 2.0x 全部成功，输出 WAV 可播放且时长排序正确。
- 证据：[system-tts-matrix-final4-instrumentation.txt](../../artifacts/qa/phase-9/system-tts-matrix-final4-instrumentation.txt)、[system-tts-matrix-final4.logcat.txt](../../artifacts/qa/phase-9/system-tts-matrix-final4.logcat.txt)。
- 修复点：使用 `setSpeechRate()` / `setPitch()`，不再把错误类型的 rate/pitch 参数塞入 Bundle；`onDone` 后在 IO 线程 bounded retry 解析 WAV，避免回调阻塞和文件 flush 竞态。

## MiniMax real test

- PASS：真实连接 preflight、上传/克隆、`t2a_v2` 合成、WAV 落盘；证据：[minimax-real-chain-final5-instrumentation.txt](../../artifacts/qa/phase-9/minimax-real-chain-final5-instrumentation.txt)、[minimax-real-chain-final5.logcat.txt](../../artifacts/qa/phase-9/minimax-real-chain-final5.logcat.txt)。
- PASS：同一短指纹 voice 真实完成 zh-CN / en-US / ja-JP；zh/en 各 0.75x / 1.0x / 1.5x 均成功，Japanese 1.0x 也成功。证据：[minimax-multilingual-final3-instrumentation.txt](../../artifacts/qa/phase-9/minimax-multilingual-final3-instrumentation.txt)、[minimax-multilingual-final3.logcat.txt](../../artifacts/qa/phase-9/minimax-multilingual-final3.logcat.txt)。
- 输出使用官方支持的 hex 音频路径，保留 URL 响应兼容解析；语言通过独立 `language_boost` 字段映射，voice id 未被语言切换改写。参考：[MiniMax T2A API](https://platform.minimaxi.com/docs/api-reference/speech-t2a-http)。
- PASS：错 Key 归类为中文业务错误且不泄漏测试 Key；证据：[minimax-wrong-key-instrumentation.txt](../../artifacts/qa/phase-9/minimax-wrong-key-instrumentation.txt)、[minimax-wrong-key.logcat.txt](../../artifacts/qa/phase-9/minimax-wrong-key.logcat.txt)。
- PASS：飞行模式下归类为 `NetworkUnavailable`，文案为“网络不可用，请检查网络连接”；证据：[minimax-offline-instrumentation.txt](../../artifacts/qa/phase-9/minimax-offline-instrumentation.txt)、[minimax-offline.logcat.txt](../../artifacts/qa/phase-9/minimax-offline.logcat.txt)。

MiniMax test credential: loaded from local secure test file

## UI regression

- PASS：首页、历史、设置、诊断展开、历史播放、语言选择和速度滑杆均完成真机交互。
- 首页证据：[ui-home-final-main.png](../../artifacts/qa/phase-9/ui-home-final-main.png)、[ui-home-final-main.xml](../../artifacts/qa/phase-9/ui-home-final-main.xml)。
- 历史滚动/播放证据：[ui-history-final.png](../../artifacts/qa/phase-9/ui-history-final.png)、[ui-history-playing.xml](../../artifacts/qa/phase-9/ui-history-playing.xml)。
- 设置/诊断证据：[ui-settings-final.png](../../artifacts/qa/phase-9/ui-settings-final.png)、[ui-settings-diagnostics.xml](../../artifacts/qa/phase-9/ui-settings-diagnostics.xml)。
- 正式包首启和本地生成证据：[release-home-fresh-final.xml](../../artifacts/qa/phase-9/release-home-fresh-final.xml)、[release-fresh-generation.xml](../../artifacts/qa/phase-9/release-fresh-generation.xml)。

## Release APK

- PASS：版本源 `version.properties` 为 `1.0.1 / 1000001`。
- PASS：正式包 [ShineVoice-V1.0.1-release.apk](../../dist/apk/release/ShineVoice-V1.0.1-release.apk) 已构建、安装、启动，并完成本地真实生成。
- PASS：包名 `com.shinevoice`，v2 签名，单 signer，固定证书指纹匹配，16K zipalign 通过。
- 当前 APK：193,169,190 bytes；SHA-256 `02962a0e43411954393006c3083360eacd59d58f41db620ccb094a223d98d6bf`。
- PASS：`update.json` 已生成并由 `verify-release-metadata.js` 校验，版本、文件名、大小和 SHA-256 一致。证据文件：[update.json](../../dist/apk/release/update.json)。
- 修复了 `apksigner` 新旧证书输出格式兼容性；验证脚本仍固定匹配预期发布证书，不放宽 signer 门禁。

## Update protocol

- PASS：单元测试覆盖正常更新、无降级、forceUpdate/minimumVersionCode、draft/prerelease、缺资产、错误资产名、畸形 metadata、GitHub HTTPS host，以及临时下载/哈希/包名/证书校验合同。
- PASS：更新检查只有在成功取得合法 Release 后写入 24 小时节流时间；失败可重试，force check 绕过节流。
- PASS：本地 V1.0.1 `update.json` 的生成与验证脚本均通过。
- 当前远端 latest 在验收时仍是 V1.0.0；V1.0.1 尚未发布到 GitHub，因此未将“线上 latest 已发现 V1.0.1”冒充为 PASS。

## Upgrade E2E

- PASS：从不可变 `V1.0.0` tag 独立构建同 signer 的 `versionCode=1000000` 正式包，安装后用系统 TTS 生成 1 条历史记录；再执行 `adb install -r` 安装 V1.0.1。
- 升级后历史页仍显示 `// 01` 及原文本，证明应用数据未被清空。证据：[upgrade-v100-history.xml](../../artifacts/qa/phase-9/upgrade-v100-history.xml)、[upgrade-v101-history.xml](../../artifacts/qa/phase-9/upgrade-v101-history.xml)。
- V1.0.0/V1.0.1 包信息证据保留在验收终端输出；V1.0.0 tag 未修改。

## Secret audit

- PASS：tracked source grep 未发现真实 API Key、Bearer token、密码或 keystore 内容；测试 Key 只在内存管道中使用，日志仅保留业务分类、长度和截短 voice fingerprint。
- PASS：AAR、模型、参考音频、APK、dist、artifacts、测试输出均保持 Git ignored；`.zcode/` 原有未跟踪内容未触碰。
- 证据：提交前执行 `git grep -n -I -E 'sk-|Bearer |MINIMAX-TEST|invalid-key-e2e'` 并人工检查命中均为协议代码/占位测试文本，不含秘密值。

## CI / GitHub Release

- CI workflow 已加入 `:app:lintDebug` 门禁；本地等价 test/lint/debug build 已通过。
- GitHub Release V1.0.1 发布动作取决于当前 GitHub CLI 认证状态；验收时本机 keyring token 无效，因此未伪造 Release 已发布结论。推送与远端 Actions 结果以最终命令输出为准。

## Remaining risks

- 上游 sherpa-onnx ZipVoice 1.13.x 的快速原生路径仍存在已知稳定性风险；应用已用 1.0x native + PCM fallback 保护 2.0x 产品档位。
- 系统 TTS 的可用语种/音色由设备实际引擎决定；本次设备中英均可用，其他语言应按设备能力显示。
- V1.0.1 正式资产尚需在具备有效 GitHub 发布权限的环境中创建 tag/release 并上传 APK 与 `update.json`；线上 latest 检查和另一台旧设备的公网安装跳转需在发布后复验。
