# ShineVoice Release 检查清单

## Source

- [ ] 工作区无未预期修改，版本只改 version.properties。
- [ ] git diff --check 通过。
- [ ] rg 检查未发现 API Key、Bearer token、密码或密钥内容。
- [ ] CHANGELOG.md 与 Release notes 已更新。

## Build

- [ ] :app:testDebugUnitTest 通过。
- [ ] :app:lintDebug 通过。
- [ ] :app:assembleDebug 通过。
- [ ] 未注入签名变量时 release 任务 fail closed。
- [ ] 正式包构建成功，文件名为 ShineVoice-V<versionName>-release.apk。

## APK

- [ ] zipalign 通过。
- [ ] APK v2 签名存在。
- [ ] signer 数量为 1。
- [ ] 包名、versionCode、versionName 与 version.properties 一致。
- [ ] SHA-256 与 update.json 一致。
- [ ] 发布证书指纹与固定 signer 一致。

## Runtime

- [ ] Debug APK 可安装并启动。
- [ ] 创作页语言选择器可用，Provider 切换后能力集合正确。
- [ ] 本地中文 / English 生成各至少一次并可播放。
- [ ] 系统 TTS 语言与音色按设备实际能力显示。
- [ ] MiniMax 使用同一 voice_id 完成至少两种语言、两档 speed 的真实请求；日志只保留截短指纹。
- [ ] Room 2→3 升级不丢历史。
- [ ] 更新元数据解析、下载临时文件、哈希 / 包 / 证书校验、系统安装器跳转均已验证。

## GitHub

- [ ] push 到目标分支后远端 commit 可见。
- [ ] tag 使用 V<major>.<minor>.<patch>。
- [ ] Release 为正式发布，资产包含 APK 与 update.json。
- [ ] 另一台安装旧版本的设备能从 latest Release 检查并进入系统安装器。
