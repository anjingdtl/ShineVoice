# GitHub Release 应用内更新协议

## 服务端约定

仓库默认指向 anjingdtl/ShineVoice。最新版本入口固定为：

https://github.com/anjingdtl/ShineVoice/releases/latest

每个正式 Release 必须包含：

- Tag：V<major>.<minor>.<patch>，例如 V1.0.0。
- 正式 APK：ShineVoice-V<versionName>-release.apk。
- update.json：包含 versionName、versionCode、apkName、apkUrl、sha256、apkSizeBytes、force、minimumVersionCode、title、notes。
- Release 不能是 draft 或 prerelease。

APK URL 只允许 GitHub Release 资产域名；应用不接受任意第三方下载地址或重定向后的非 GitHub 主机。

## 客户端流程

1. 关于页手动检查；应用首次进入根界面后按 24 小时节流自动检查。
2. 读取 latest Release 与 update.json，验证 tag、字段类型、版本号、资产名称、资产 URL、SHA-256 与大小。
3. 仅当远端 versionCode 大于本地 versionCode 时显示更新。
4. 下载到 app cache 的 updates/ 目录，使用 .part 临时文件；失败时清理临时文件并保留可诊断错误。
5. 流式计算 SHA-256，检查精确字节数，再通过 PackageManager 校验包名、versionCode、单 signer 与固定证书指纹。
6. 仅通过 Android 系统安装器安装。用户需要在系统设置中允许本应用安装未知来源；应用不执行静默安装、不覆盖已下载文件、不安装未验证文件。
7. 强制更新由 force=true 或本地 versionCode 低于 minimumVersionCode 触发，并隐藏“稍后”操作。

## 失败处理

网络失败、元数据错误、资产不匹配、哈希错误、包身份错误、证书错误和安装权限不足均在 UI 中显示可行动提示；日志只记录 URL 主机、版本、大小和截短指纹，不记录 API Key、完整 voice_id 或签名秘密。
