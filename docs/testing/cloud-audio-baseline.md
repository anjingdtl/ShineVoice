# M0 基线诊断记录（云端 + 音频）

> 记录日期：2026-09-25。诊断执行环境：本机 Windows + emulator-5554（Medium_Phone, Android 17/API 37, x86_64）。
> 代码基线：`ad290b5`（App 内已安装版本为 1.0.0-debug）。
> 密钥来源：`C:\Users\Administrator\Desktop\AIstudio\Test-key\MINIMAX-TEST.txt`（授权测试凭据，本文不含任何密钥内容）。

## 1. 云端"连接失败：无法读取云端音色列表"定位

### 1.1 一手 API 契约核验（2026-09-25 抓取官方文档）

- 大陆文档站已从 `platform.minimaxi.com` 302 重定向到 `platform.minimax.cn`。
- get_voice：`POST {base}/v1/get_voice`，body `{voice_type}`，枚举 `system | voice_cloning | voice_generation | all`。
  - 响应：`system_voice[] {voice_id, voice_name, description[]}`；`voice_cloning[] {voice_id, description[], created_time}`；`base_resp {status_code, status_msg}`（0=成功，2013=参数错误）。
  - **克隆音色需首次成功合成后才会出现在查询结果中**（官方明确说明）。
- t2a_v2：`POST {base}/v1/t2a_v2`，`output_format=hex|url`；`audio_setting.format=wav`、`sample_rate=24000` 支持；错误码 1004 鉴权 / 1002、1039 限流 / 1001 超时 / 2013 参数 / 1008 余额（通用错误表）。
- 官方文档 base 为 `https://api.minimax.cn/v1`（备份域名 `api-bj.minimaxi.com`）；国际站 `https://api.minimax.io/v1`。

### 1.2 真实网络测试（宿主机，python urllib，密钥仅在内存中传递）

| 请求 | 结果 |
| --- | --- |
| `api.minimax.cn` get_voice(system) | HTTP 200，base_resp=0，**303 个系统音色** |
| `api.minimax.cn` get_voice(voice_cloning) | HTTP 200，base_resp=0，**5 个已克隆音色**（`sv` 前缀 18 位，为本 App 早前测试创建） |
| `api.minimaxi.com` 同上 | 与 minimax.cn 完全一致（旧域名仍可用） |
| `api.minimax.cn` t2a_v2（系统音色，speech-2.8-hd，wav/24k/hex） | HTTP 200，base_resp=0，3.8s WAV，RIFF 头正确，peak=0.448，无削波 |
| `api.minimax.cn` t2a_v2（克隆音色） | HTTP 200，base_resp=0，4.0s 音频 |

**结论：测试密钥有效、具备目录与合成能力；两个大陆域名当前均可用。**

### 1.3 App 内复现（emulator-5554）

1. 冷启动修复模拟器网络前：App「测试连接」→ **"连接失败：网络不可用，请检查网络连接。"**（与用户截图同类失败；模拟器状态栏 WiFi 带 "!"，DNS 全域解析失败）。
2. 冷启动修复网络后（同版本 1.0.0、同密钥、同区域"中国大陆"）：App「测试连接」→ **"云端连接正常"**，列出全部 5 个克隆音色。

### 1.4 根因结论

- 用户遇到的"连接失败"为**环境性网络故障**（复现证据：模拟器 NAT/DNS 失效时同版本 App 报同款错误）。代码中 `无法读取云端音色列表` 文案仅在"2xx 但响应体不是合法 JSON"（如网关/WAF 拦截页）或未知异常时出现，同样属网络路径类问题。
- **App 侧真实缺陷（与用户报错共生，需修复）**：
  1. `listVoices` 只查 `voice_cloning`，`validateConfig` 用它验证整个云服务 → 无克隆音色的账号无官方音色入口，目录能力与克隆权限耦合。
  2. 非 2xx 只读 HTTP 状态码；2xx 非法 JSON/HTML 只给笼统文案，无诊断编号；`{}` 空体会被当作成功（当前代码 `body?.string() ?: "{}"`，但 OkHttp 空体返回 `""`，`JSONObject("")` 抛异常落入笼统文案）。
  3. 大陆域名 `api.minimaxi.com` 为旧域名（官方文档已迁移 `api.minimax.cn`；当前两者等价可用，需跟进新域名）。
  4. Key 不做首尾空白规范化；区域切换立即落盘；「测试连接」也静默保存表单；解密失败与未配置不可区分。
  5. 设置页标题行内嵌长状态文本（错误消息整段塞进状态 chip）→ 标题/按钮挤压换行（用户截图问题）。
  6. 协程取消可能被 `runCatching` 吞掉；重复点击测试无互斥；清除配置不取消在途请求。

## 2. 本地音频链路基线

### 2.1 已确认代码缺陷（M2 修复清单）

| 缺陷 | 位置 | 影响 |
| --- | --- | --- |
| 录音 stop() 在 UI 线程 join(3s)；录音线程与调用端双重 stop/release；`MutableList<Short>` 装箱增长；stop 以"旧文件存在且 >44B"判成功 | `VoiceRecorder.kt` | 主线程阻塞；资源竞态；**录音失败时旧 reference.wav 冒充新录音** |
| WAV writer 用 "rw" 打开不截断 | `PcmAudio.kt MonoWavWriter` | 短录音覆盖长录音残留旧尾部 |
| WAV reader 多声道只读 2 样本/帧但按全帧推进 | `PcmAudio.kt MonoWavReader` | >2 声道文件帧错位 → 全文垃圾数据 |
| data chunk 不补偶数字节 padding；无块长度溢出校验 | 同上 | 畸形文件误读 |
| 线性插值重采样，无抗混叠 | `Resampler` | 44.1k/48k→24k 时 >12kHz 成分混叠入可听域（嘶嘶声/毛刺） |
| MediaCodec 在 configure/start 前取 inputBuffers/outputBuffers；不读输出声道/PCM 编码；PCM_FLOAT 无分支 | `AudioCodecDecoder.kt` | 导入压缩音频在部分设备抛 IllegalStateException 或误读 float PCM |
| speed>1 生成后重采样加速（变调不变速语义错误） | `AudioSpeedScaler` | 已知变调行为（保持 native 1.0x 保护是对的） |
| `GeneratedAudio.save` 直接落盘，无重读校验 | `SherpaZipVoiceProvider` | 无"文件可解码"保证（save 本身有钳位，见 2.2） |
| 播放 `prepare()` 同步调用在主线程；Activity 销毁即 release 后台播放 | `AudioPlaybackController` | 大文件/坏文件卡 UI；跨应用不可用 |
| SPEAKER 分支恒返回 true，不验证实际输出设备 | `AudioRouteManager` | 蓝牙连接时"外放"实际走耳机 |

### 2.2 native 输出转换核验（重要排除项）

- sherpa-onnx v1.13.8 `wave-writer.cc`：`std::clamp<int32_t>(samples[i]*32767, -32768, 32767)` —— **饱和转换，无整数回绕**。排除"float 超界回绕导致电流声"假设。

### 2.3 基线音频指标（修复前样本，可靠拉取 `adb exec-out run-as ... cat`）

> ⚠️ 取证方法教训：`adb shell run-as ... cat file > local` 在 Windows Git Bash 下会被 pty 做 CRLF 插入，**破坏二进制并伪造"满幅削波"读数**（曾得到 peak=1.0/clip 0.05~0.11% 的假象）。所有音频取证必须使用 `adb exec-out`。下表为 exec-out 重新拉取后的真实指标：

| 文件 | 时长 | 采样率 | peak | RMS | 削波率 | SHA-256（前16） |
| --- | --- | --- | --- | --- | --- | --- |
| default reference.wav | 6057ms | 24k 单声道 | 0.739 | -20.9dBFS | 0 | 72e25f11b3fffc40 |
| e2e-reference-12s.wav | 12113ms | 24k 单声道 | 0.739 | -20.9dBFS | 0 | 3831b298e7da2e30 |
| 本地生成 0.5x | 27349ms | 24k 单声道 | 0.764 | -24.0dBFS | 0 | 26e5877fec421a68 |
| 本地生成 1.0x | 9771ms | 24k 单声道 | 0.860 | -20.7dBFS | 0 | 3915e10c4f5286e9 |
| 本地生成 2.0x | 4885ms | 24k 单声道 | 0.779 | -20.7dBFS | 0 | d0a6ea885a9f4ac9 |
| 云端系统音色（直连 API） | 3797ms | 24k 单声道 | 0.448 | -23.3dBFS | 0 | 2fae7c00fa40e666 |
| 云端官方音色（M1 App 内生成） | 3321ms | 24k 单声道 | 0.831 | -19.5dBFS | 0 | 7aab3425066b5794 |

**结论修正：模拟器内置参考音频 + 现有生成链在本机环境下输出电平健康、零削波。** 用户报到的"电流声/杂音"来自用户手机上的自录参考音频路径，其根因候选收敛到 2.1 表中的录音器/重采样/解码缺陷（模拟器无法复现真实麦克风输入，见 2.4）。

云端 WAV 结构注意点：MiniMax 服务端 ffmpeg 封装的 WAV 含 `LIST/INFO` 附加 chunk（如 Lavf59.2x 签名），解析器必须按 chunk 遍历而非假设 data 紧跟 fmt。

### 2.4 模拟器麦克风限制

- 模拟器无真实麦克风输入（虚拟设备静音/回环不可控）。本地录音链验证采用**明确标注的测试音频**（内置默认参考 + 合成 WAV）；**真实手机录音验收单列**，不在模拟器上假称通过。

## 3. 微信外放可行性（M0 初步）

- 无用户物理手机可接入；微信收录效果**待真机验证**，本期完成悬浮播放器与外放控制，不承诺注入微信麦克风（Android 无公开通用接口）。

## 4. 环境事件记录

- 模拟器网络故障（DNS 全域失败，WiFi "!"）通过**冷启动**（`emu kill` + `emulator -avd Medium_Phone -no-snapshot-load`）修复；用户数据（已装 App、配置、历史）保留完好。
- Git Bash MSYS 路径转换会破坏 `adb shell` 内的 `/sdcard/...` 参数（转为 `/Files/Git/sdcard/...`），诊断脚本一律 `export MSYS_NO_PATHCONV=1`。

## 5. M1 修复后验证（2026-09-25，1.1.0-debug）

| 验证项 | 结果 |
| --- | --- |
| 设置页「保存并测试」（存储密钥保留场景） | ✅ 可连接，官方音色 303 · 克隆音色 5（与直连探测一致） |
| 官方音色选择（青涩青年音色）+「生成测试语音」 | ✅ 真实计费合成成功，3320ms WAV（peak 0.831 / RMS -19.5dBFS / 0 削波 / 含 LIST-INFO chunk 解析正常） |
| 密钥留空=保持已存密钥；区域为草稿保存时才落盘 | ✅ UI 行为验证 |
| 错误路径（错 Key 401 / 离线 / 非法响应） | ✅ JVM MockWebServer 契约测试 C01–C10 可行子集 + instrumented test05/06/07 真实链路通过 |
| 全新安装 → 配置 → 连接 | ✅（卸载重装后重新走完整流程） |
| 覆盖升级 1.0.1→1.1.0（1000001→1000002） | ✅ 加密密钥与数据保留，升级后「可连接 303+5」 |

## 6. M2 修复后验证（2026-09-25，1.1.0-debug）

| 验证项 | 结果 |
| --- | --- |
| 本地生成（新链路：非有限值检查 + 重读校验） | ✅ `ZipVoice generated ... audioDurationMs=4019 rereadMs=4019`；产物 peak 0.75 / RMS -21.2dBFS / 0 削波 |
| 连续 20 次稳定性（App 内置真实 Native 生成） | ✅ **20/20 (100%)**，avg 2673ms / max 2807ms，RTF avg 0.665，PSS 169→185MB（Δ16MB，含 20 个产物累积），0 失败 0 崩溃 |
| 产物抽检 | ✅ 20 个文件全部落盘；抽样 24kHz/单声道/PCM16/0 削波 |
| WAV 读写器边界（截断/多声道/奇数 padding/伪造长度） | ✅ JVM AudioPipelineTest 14 项 |
| 抗混叠重采样（44.1k/48k→24k 正弦保真 + 16kHz 混叠抑制） | ✅ 幅度误差 <1dB；8kHz 处混叠幅度 <0.05 |
| MediaCodec 新生命周期（configure→start→getBuffer；输出格式重读；PCM_FLOAT 转换） | ✅ instrumented 真实解码链回归（E2eRealChainTest 10/10） |
| 模拟器录音 | ⚠️ 模拟器无真实麦克风输入，RecordingSession 的 AudioRecord 真实采音路径**待真机验证**；标准化管线（导入路径同源）已由单测+instrumented 覆盖 |

### 6.1 音频故障根因结论（针对用户报送的"电流声/杂音"）

无法在模拟器复现用户手机的原始故障样本（未获得故障 WAV 与原始录音）。已修复的可证缺陷（均可能造成用户描述的症状）：

1. **录音失败静默沿用旧参考音频**（旧 `VoiceRecorder.stop()` 以"旧文件存在"判成功）→ 音文不一致/错音频进入推理；
2. **线性插值重采样无抗混叠**（44.1/48k→24k）→ 高频混叠为嘶嘶杂音；
3. **WAV writer 不截断** → 短录音覆盖长录音残留旧尾部（尾部垃圾数据）；
4. **MediaCodec 生命周期违规**（configure 前取 buffers）+ 不读输出声道/编码 → 导入压缩音频在部分设备失败或整段错位；
5. **多声道 WAV 帧错位读取**（>2 声道全文件垃圾数据）。

排除项（有证据）：native 输出 float→PCM16 为饱和钳位（sherpa-onnx v1.13.8 源码核验），无整数回绕；本机参考与生成链输出电平健康、零削波。
