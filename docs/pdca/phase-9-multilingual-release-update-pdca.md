# Phase 9：多语言、正式发布与应用内更新 PDCA

## Plan

把语言作为 TTS 请求的一等字段，补齐 Provider 能力声明与 UI 选择；升级 Room 时保留历史；建立可审计的正式签名 APK 和 GitHub Release 更新闭环。

## Do

- 增加稳定的 BCP-47 语言目录、能力集合、中文 / English / 日语等 UI 入口。
- MiniMax 把语言映射到独立 language_boost，不把语言写进 voice_id。
- ZipVoice 明确声明随包模型实际覆盖范围；Android System TTS 动态枚举设备音色。
- Room 增加 language、speed 字段与 2→3 显式迁移。
- 增加版本源、正式包签名 fail-closed、APK 校验、update.json 与 GitHub latest 更新协议。

## Check

- JVM 单元测试覆盖语言目录、MiniMax payload 的 voice_id / speed 独立性、更新协议版本门禁、强制更新、错误资产与严格 GitHub 域名。
- 运行 :app:testDebugUnitTest，结果为 BUILD SUCCESSFUL。
- 待正式包完成后执行签名、zipalign、包身份、SHA-256 与固定证书检查。
- 模拟器和真实 MiniMax 账号链路按 Release 检查清单执行；真实链路所需密钥只通过进程参数或环境注入。

## Act

- 若设备没有对应系统音色，保留可解释的 unsupported-language 提示，并允许用户切换 Provider。
- 若远端更新包任一身份校验失败，停止安装并清理临时文件。
- 后续版本继续补 ASR、云端音色删除与模型下载器，并追加版本迁移测试和 arm64 真机证据。
