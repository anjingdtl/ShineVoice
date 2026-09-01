# Phase 2 PDCA（音色库）

## Plan

将“音色”从固定单一路径改造成列表化用户音色库：VoiceProfile 列表、创建/编辑/重命名/删除、录音/导入音频、referenceText、设为当前、试听、最近使用、本地/云端/系统绑定状态；同一 VoiceProfile 可绑定 ZipVoice / MiniMax / Android TTS；创作页点击当前音色用底部弹层快速选择。

影响面：Room schema v1→v2（新列 + 迁移）、新增 `core/audio`（WAV/解码/重采样/录音/导入）、`domain/voice`（VoiceProfileManager）、MainViewModel 状态与创作页改造。Provider 接口与 ZipVoice Native 链路不动。

## Do

- Room v2：`VoiceProfileEntity` 增加 `sourceAudioPath`、`minimaxVoiceId`、`androidTtsEngine`、`androidTtsVoice`、`isCurrent`、`isDefault`、`lastUsedAt`；显式 `MIGRATION_1_2`；种子默认音色（指向部署的 `voices/default/reference.wav`，`referenceText` 用官方文案）。
- `core/audio`：`PcmAudio`（MonoWavReader/MonoWavWriter/Resampler，纯 JVM 可单测）、`AudioCodecDecoder`（MediaCodec 解码 MP3/M4A/AAC + 下混/重采样）、`ReferenceAudioImporter`、`VoiceRecorder`（AudioRecord PCM → 24 kHz 标准化 WAV）。
- `VoiceProfileManager`：CRUD + setCurrent/touch/delete（文件与 DB 一致，默认音色不可删）。
- ViewModel：voices/currentVoice 由 Room Flow 驱动；生成与稳定性测试改为按当前音色取 `referenceAudioPath`+`referenceText`；新增 speed、创建/重命名/删除/设当前/挂载参考音频/更新参考文本操作。
- UI：音色页（列表、绑定徽标、最近使用、当前标记、录音/导入/试听/重命名/删除、创建弹窗）；创作页“当前音色”卡片 + 底部弹层选择器 + 语速滑杆。
- Manifest：新增 `RECORD_AUDIO` 权限。
- 测试：`PcmAudioTest`（WAV 往返、重采样比例、空速率恒等、时长计算）。

## Check

- CI：`:app:testDebugUnitTest` + `:app:assembleDebug` 由 GitHub Actions 执行（见对应 commit run）。
- Room 迁移：v1→v2 显式迁移；真机升级验证留待真机验收。
- 录音/导入/MediaCodec 解码、权限弹窗、底部弹层、文件与 DB 删除一致性：无法在远端 CI 验证，标记为**待真机验收**。
- ZipVoice 生成链路无改动，不回归。

## Act

- 若 CI 报错即修复后重推。
- 待真机验收项：真实录音→导入→参考文本→本地生成的完整闭环；删除音色后文件与 DB 一致性；创作页底部弹层选择；语速参数对生成效果的影响。