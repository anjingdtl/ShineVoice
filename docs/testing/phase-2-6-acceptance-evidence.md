# Phase 2~6 本地综合验收证据（模拟器真机链路）

日期：2026-09-01。设备：`emulator-5554`（sdk_gphone16k_x86_64 / Medium_Phone，API 37.1）。
构建：`gradlew :app:testDebugUnitTest`（27/27 通过）+ `:app:assembleDebug` + `installDebug`。
真机链路验证方式：App UI 操作（截图/语义树/日志取证）+ `androidTest` instrumented 真实链路测试（`E2eRealChainTest`）。

## MiniMax 真实联调（P0）

凭据：本机测试文件（不入库、不写日志，仅以 BYOK 输入 App 与 `-e` 命令行参数传入 instrumented 测试）。
区域：中国大陆 `https://api.minimax.cn`，GroupId 留空（现行 sk- 密钥仅需 Bearer）。

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| 保存 BYOK + 测试连接 | 通过 | UI Toast「云端连接正常。」；instrumented `connection OK: 云端连接正常`（HTTP 200） |
| 上传参考音频（16.17 s WAV） | 通过 | `files/upload`（multipart purpose=voice_clone）返回 file_id |
| 创建克隆音色 | 通过 | `voice_clone`（JSON file_id+voice_id）成功，voice_id 18 位、字母开头，符合官方格式规则 |
| 写入 VoiceProfile | 通过 | 音色卡「云端·已绑定」徽标点亮；DataStore defaultVoiceId 写入 |
| t2a_v2 真实生成 | 通过 | 三次成功：UI 10190 ms / instrumented 3455 ms（156646 B）/ 3370 ms（178936 B），model=speech-2.8-hd，WAV 头校验 RIFF/WAVE |
| APP 重启后已有云端音色复用 | 通过 | 重启后新进程用 DB 中 voice_id 生成成功（UI「生成成功：7809 ms」；instrumented 新进程链路通过） |
| 错误 API Key | 通过 | 服务端 401/base_resp 1004 → 中文「API Key 无效，请检查后重试。」，不回显 Key |
| 无网络（飞行模式） | 通过 | `NetworkUnavailable`「网络不可用，请检查网络连接。」 |
| 网络恢复 | 通过 | 关闭飞行模式后再次真实生成成功（3370 ms） |
| 限流 | 通过（负向） | base_resp 1039「当前每分钟字符数超过限制」透传展示，等待窗口后恢复 |
| 无效 voice_id | 通过（负向） | 真实服务端返回 2054 "voice id not exist"；新增中文映射「云端音色不存在或已过期，请重新克隆。」（单测覆盖） |
| timeout | 通过（单测） | 本地 ServerSocket 静默连接触发读超时 → `GenerationTimeout`「云端请求超时」 |
| 上传失败 | 通过（单测+本地校验） | >20 MB 本地拒绝（InvalidReferenceAudio）；<10 s 由服务端拒绝路径经 mapApiError 映射 |
| voice_id 格式校验 | 通过（单测） | 非法格式（数字开头/过短/尾划线/非法字符/超长）全部拒绝 |

过程中发现并修复的缺陷：`url()` 将版本前缀拼接两次（`/v1/v1/...` → HTTP 404）；重启后 `minimaxStatus` 未恢复导致云端按钮禁用。

## ZipVoice 本地链路回归（20 次稳定性）

instrumented `test01_zipVoiceTwentyRealGenerations`（app 自身装配，真实 Native 推理）：

```text
E2E_STABILITY_20 successes=20/20 failures=0
avgMs=1462 maxMs=1596 avgRtf=0.204 maxRtf=0.235
pssBeforeKb=370332 pssAfterKb=752272 deltaKb=381940
```

无 Crash/ANR/Native Error；产物全部为有效 WAV（RIFF/WAVE 校验）。

## Android System TTS

instrumented `test02`：

- 引擎枚举：`Speech Recognition and Synthesis from Google`（系统默认，真实 PackageManager 查询）；
- 中文语音 16 个（`cmn-cn-x-ccc-local` 等），真实 `synthesizeToFile` 中文朗读成功且 WAV 有效；
- `supportsOffline` 依据 `Voice.isNetworkConnectionRequired()` 真实判定（=true，本地语音）；
- 设置页可选引擎/语音（DataStore 持久化），音色卡可绑定/解绑系统语音。

## 三种生成方式切换

instrumented `test04_providerSwitchCycle`：本地↔系统交替 10 轮（20 次真实生成）+ 1 次云端尝试，0 失败，无状态串线/选错 Voice/Player 冲突/Provider 泄漏/Crash。

## 音色库（Phase 2 回归 + 新增）

- 列表化 + 名称/当前音色状态/本地/云端/系统三绑定徽标/参考音频就绪态/最近使用（UI 语义树取证）；
- 创建（含参考文本）/编辑/重命名/试听/设为当前/删除可见可用；「克隆到云端（MiniMax）」真实成功并写回绑定；
- 同一用户音色保持一个 VoiceProfile（我的声音 = 本地+云端+系统三个 Binding）；
- `VoiceReferenceStatus` 逐音色按自身 `referenceAudioPath`+`referenceText` 判定（与默认 reference.wav 解耦），创作页本地模式显示友好就绪提示。

## 历史（Phase 3 回归）

- 日期分组（今天/08月23日，旧日期折叠、今天默认展开）、条数统计（UI 语义树取证：今天 34 条、08月23日 22 条）；
- 多选/全选可见可用；批量删除新增确认对话框（「删除生成记录」对话框在语义树中确认出现）；
- DB 与 WAV 一致性：删除走 Repository（DB 行 + 物理文件同删）；
- ZIP 导出：`AudioExporterTest` 证明导出 ZIP 可解压、条目为 RIFF/WAVE、中文文件名保留。

## 主题与设置

- 亮/暗切换即时生效（像素亮度取证：亮色 228.5 / 暗色 46.7），重启后保持（38.7），DataStore `theme_mode` 持久化确认；
- 设置结构：模型与服务（本地模型 Registry：安装状态/当前模型/选择/重新检测/添加模型指引）/外观/音频/存储/隐私/高级（开发与诊断：引擎状态、模型目录、SHA-256、参考状态、20 次稳定性入口、日志环形缓冲、导出诊断信息）/关于；
- 创作页无工程术语（截图走查：当前音色/生成方式/文本/语速/生成/播放/保存/分享）。

## 生命周期

force-stop / 冷启 / install -r / 页面切换 / 弹层开关均无 Crash（除一次外部 DB 手术竞态导致的 SQLiteDiskIOException，非产品代码路径）；本轮所有 instrumented 测试进程为全新进程（等价于重启后状态）。

## 已知限制（见 PDCA）

- 云端音色删除接口未路由（提示走控制台）；
- 上传参考音频 <10 s 的服务端拒绝仅在单测覆盖本地校验，未消耗真实额度复测；
- 模拟器存在外部并行 UI 干扰（Google Lens/ShineWriter 抢前台），部分 UI 细节改用语义树/像素亮度/日志取证；
- `get_voice` 对该测试账号不返回克隆列表（克隆后需使用一次才可查询的官方限制），App 内「云端音色列表」可能为空，不影响生成。
