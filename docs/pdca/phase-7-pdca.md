# Phase 7 PDCA（Phase 2~6 剩余验收收束 + MiniMax 真实联调）

## Plan

修复 Phase 2~6 验收遗留：MiniMax 旧 API 契约彻底重写（files/upload → voice_clone → t2a_v2，区域可配、output_format、voice_id 格式校验）；VoiceProfile 参考状态按音色自身判定；Android System TTS 引擎枚举/选择/持久化；创作页去工程化；本地模型管理抽象；历史删除确认；并在模拟器上完成真实综合验收（20 次稳定性、三方式切换、主题、生命周期、MiniMax 真实账号链路与负向路径）。

影响面：`provider/minimax/*`、`data/settings/MiniMaxConfig.kt`、`provider/androidtts/AndroidSystemTtsProvider.kt`、`domain/voice/VoiceProfileManager.kt`、新增 `domain/model/LocalModelRegistry.kt`、`ui/MainViewModel.kt`、`ui/ShineVoiceRoot.kt`、`ui/VoicesScreen.kt`、`ui/HistoryScreen.kt`、`core/log/AppLogger.kt`、Manifest（INTERNET 权限）、新增 `androidTest E2eRealChainTest` 与 `AudioExporterTest`。

## Do

- MiniMax 客户端按当前官方文档重写：连接/列表走 `POST /v1/get_voice`（voice_type=voice_cloning）；克隆两步（multipart 上传 `purpose=voice_clone` → `file.file_id`，再 JSON `{file_id, voice_id}`）；t2a_v2 使用 `speech-2.8-hd` + `voice_setting`/`audio_setting` 分离 + 顶层 `output_format=url`（hex 兜底，绝不假设 base64）；voice_id 客户端校验官方规则（8~256、字母开头、`[A-Za-z0-9_-]`、不以 `-_` 结尾）；区域（CN/GLOBAL）持久化、GroupId 选填；base_resp 1004/2038/1002/1039/1001/1043/2054/2013 全部映射中文业务错误。
- 真实联调发现并修复两处缺陷：① `url()` 版本前缀重复拼接（`/v1/v1/*` → 404，curl 双向对照证实）；② 重启后 `minimaxStatus` 未恢复导致云端生成入口被禁。
- VoiceReferenceStatus：每音色只看自己的 `referenceAudioPath`+`referenceText`；创作页移除参考文本（移至创建/编辑音色），仅保留用户语言元素。
- AndroidSystemTtsProvider：引擎枚举（TTS_SERVICE intent）、用户选择（DataStore 持久化）、指定引擎初始化、中文语音枚举/选择、语速/音高/synthesizeToFile/stop/shutdown、音色级系统绑定；`supportsOffline` 按 `Voice.isNetworkConnectionRequired()` 真实判定。
- LocalModelRegistry/ModelProfile：设置页本地模型列表/状态/当前模型/选择/重新检测/添加指引；AppLogger 环形缓冲 + 开发与诊断日志展示与导出。
- 历史批量删除增加确认对话框；ZIP 导出单测（可解压、RIFF/WAVE、中文名）。
- Manifest 补 INTERNET/ACCESS_NETWORK_STATE。
- 新增 `E2eRealChainTest`（instrumented）：20 次真实 ZipVoice 稳定性、系统 TTS 真实引擎/语音/合成、MiniMax 全链路（密钥仅经 `-e` 参数）、Provider 切换循环、错误 Key、断网（配合飞行模式）。

## Check

- 单测：27/27 通过（MiniMax 契约 19、ProviderRegistry 4、PcmAudio 2、AudioExporter 2）。
- 构建/安装：`:app:assembleDebug` 成功并 `installDebug` 到 emulator-5554。
- 真实链路结果见 `docs/testing/phase-2-6-acceptance-evidence.md`：MiniMax 连接/克隆/t2a/重启复用/错误 Key/断网/恢复/限流/无效 voice_id 全覆盖；ZipVoice 20/20（avg 1462 ms、avg RTF 0.204、无 Crash/ANR）；系统 TTS 16 中文语音真实合成；三方式切换 20 次交替 0 失败；主题亮/暗/重启保持；历史分组/折叠/全选/删除确认取证。
- 过程问题：模拟器存在外部并行 UI 干扰（Google Lens/ShineWriter 抢前台、输入法自动输入、UI dump 陈旧树），故部分验收改用 instrumented 测试 + 语义树 + 像素亮度 + 日志取证；外部 DB 手术曾触发一次性 SQLiteDiskIOException（竞态，非产品路径）。

## Act

- 修复项已全部落地并复测：URL 拼接、重启状态恢复、2054 映射、INTERNET 权限、SHA-256 文案下沉诊断区。
- Commits：`1350773`（MiniMax 契约重写）、`3918c3c`（VoiceReferenceStatus+创作页收束）、`99e56ba`（系统 TTS/模型抽象/删除确认）、`54b5719`（URL 修复+重启恢复+E2E 测试）。
- 遗留待办：云端音色删除接口路由（官方 Voice Management delete 端点）；`get_voice` 列表对「未使用过的克隆音色」不返回（官方限制，UI 提示需考虑）；真机（arm64）复测；外部 DB 手术竞态仅测试环境问题，无产品影响。
