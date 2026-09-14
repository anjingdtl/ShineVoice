# Changelog

## V1.0.1 — 2026-09-14

### Fixed

- 系统 TTS 每次请求显式应用语速与音调，异步解析输出 WAV，避免引擎回调阻塞与短暂文件竞态。
- ZipVoice 运行时升级到官方 sherpa-onnx 1.13.8；对已知快速档位的原生缓冲区风险启用安全的 PCM 后处理加速。
- MiniMax 云端合成使用 hex 输出，统一 WAV 解析，补齐中国大陆端点、语言映射、错 Key 与断网错误分类。
- 更新检查成功后才记录 24 小时节流时间，失败与 force check 不再被错误跳过。
- CI 增加 lint 门禁，补齐 Room 2→3、音频速度缩放、WAV 解析与更新节流测试。

### Verified

- 在 Android API 37 x86_64、16 KB page-size 模拟器上完成 ZipVoice 中英 0.5/1.0/2.0x、系统 TTS 中英速度矩阵、MiniMax 同 voice_id 多语言真实合成与 21 次三方轮转验收。

### Boundary

- 随包 ZipVoice 模型仍覆盖中文 / English；2.0x 通过应用层 PCM 后处理实现，规避上游 sherpa-onnx 1.13.x ZipVoice 快速原生路径的已知稳定性问题。

## V1.0.0 — 2026-09-08

### Added

- 语言目录与 Provider 能力声明，创作页可选择中文、English、日本語等语言。
- MiniMax language_boost 映射；voice_id 与 speed 保持独立。
- Android System TTS 动态枚举设备实际音色与语言。
- 设置页系统音色改为紧凑下拉选择器，语种分组可折叠，音色列表独立滚动。
- 生成历史保存并展示语言与语速。
- GitHub Release 检查、下载、SHA-256 / 包名 / 版本 / 固定证书校验，以及系统安装器确认流程。
- 正式包构建、签名校验、更新元数据生成与校验脚本。

### Changed

- Room 数据库升级到 version 3，使用 2→3 显式迁移，不再以 destructive fallback 掩盖升级问题。
- 版本源统一到 version.properties：versionName 1.0.0、versionCode 1000000。
- 空文本与错误提示改为不绑定中文的通用文案。

### Boundary

- 随包 ZipVoice 模型当前覆盖中文 / English；云端语言以 MiniMax 官方能力和映射为准；系统语言以设备安装音色为准。
- 自动识别参考文本、云端音色删除接口与模型下载器仍留在后续版本。
