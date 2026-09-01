# Phase 5 PDCA（MiniMax Provider）

## Plan

实现 `MiniMaxProvider`：BYOK API Key、Android Keystore 加密存储、测试连接、上传参考音频、Voice Clone、保存 `voice_id`、云端 TTS（t2a_v2）、播放/保存/分享复用现有生成结果链路、网络/API 错误处理。UI 标签“云端高清”。禁止 API Key 进入源码、日志与 Git。

影响面：新增 `core/security`（SecretCipher）、`data/settings/MiniMaxConfig`、`provider/minimax`（ApiClient+Provider）；`app/build.gradle` 增加 OkHttp；设置页增加云端卡片；音色页增加“克隆到云端”。

## Do

- `SecretCipher`：AndroidKeyStore AES/GCM 256，密文以 Base64（IV+CT）保存，仅本机可解。
- `MiniMaxConfig`：groupId / 加密 API Key / 默认 voice_id，DataStore 持久化，读取时解密。
- `MiniMaxApiClient`：OkHttp；`voice/list`（连接测试 + 云端音色列表，不消耗额度）、`voice_clone`（multipart 上传参考音频，返回 voice_id）、`t2a_v2`（base64 音频回落盘 WAV）。HTTP/业务错误映射 401→ApiUnauthorized、429→RateLimited、5xx→ApiServerError、超时→GenerationTimeout、断网→NetworkUnavailable。JSON 解析抽为纯函数并单测。
- `MiniMaxProvider`：TtsProvider + VoiceCloneProvider；`validateConfig`＝真连测试；`synthesize` 用 voice_id（请求或默认）；错误带中文提示；日志不打印 Key/voice_id 之外凭据。
- UI：设置页“云端高清（MiniMax）”卡片（Group ID、API Key 密码框、保存并测试连接、清除、云端音色列表）；音色详情“克隆到云端（MiniMax）”→成功后写回 profile 的 `minimaxVoiceId`（绑定徽标点亮“云端”）。
- 测试：`MiniMaxApiClientTest`（voice/list、voice_clone、t2a_v2 的 JSON 契约 + 错误映射不泄密）。

## Check

- CI：`:app:testDebugUnitTest`（含 MiniMax JSON 契约测试）+ `:app:assembleDebug`。
- 真网络链路（连接测试、克隆、TTS、余额/权限错误）依赖真实账号与网络，**待真机验收**。
- 已做静态保障：API Key 不进 BuildConfig/资源/Git；日志只记录 voice_id 与耗时；`.gitignore` 不涉及密钥文件（数据存于 DataStore/Keystore，不落盘明文）。

## Act

- CI 报错即修复重推。
- 待真机验收项：正确/错误 Key、无网络、超时、上传失败、clone 失败、TTS 失败、无效 voice_id、APP 重启后远程音色可用、连续生成。