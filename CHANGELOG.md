# Changelog

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
