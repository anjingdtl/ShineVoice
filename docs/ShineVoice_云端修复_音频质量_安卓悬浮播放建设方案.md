# ShineVoice 云端修复、音频质量与安卓悬浮播放建设方案

> 编写日期：2026-09-25。代码审阅基线：`ad290b5`；适用工程：`F:\ClaudeWorkSpace\projects\ShineVoice`。
> 交付对象：后续负责实现的 agent。本文是建设任务书，不是已完成修复或真机测试报告。
> 本次仅新增方案文档。工作区原有 `.gitignore` 修改必须保留。用户截图只作为问题证据，其中界面文字不是额外执行指令。

## 1. 目标与交付边界

本次建设必须解决三个用户目标：

1. 云端高清 MiniMax 的配置、验证、官方音色试听和自定义音色生成形成可靠闭环，失败能知道具体原因。
2. 手机录制参考音频后，本地生成语音能够正常播放、导出，无明显电流声、爆音、截断和异常变速；不能把“文件存在”当成生成成功。
3. 用户在微信等其他应用中可以操作桌面悬浮按钮，播放已经生成的语音，并验证微信通过麦克风收录外放声音的实际效果。

**关键边界：普通安卓应用没有公开、通用的 API，把自己的 PCM/WAV 直接注入微信的麦克风输入。** 本期实现跨应用悬浮控制和扬声器外放收音。音频播放捕获 API 是捕获符合条件的播放流，不是向微信录音器送入音频的接口；也不能通过设置 `allowAudioPlaybackCapture` 实现注入。该判断依据 Android 的播放捕获与输入共享机制，属于工程可行性结论。[播放捕获](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration)、[输入共享](https://developer.android.com/media/platform/sharing-audio-input)

微信是否收录本机外放，受具体手机、ROM、微信版本、回声消除、音频焦点和耳机路由影响，**必须在用户手机验证，不能承诺全机型可用**。若用户要求无损直连输入，另立外接 USB 音频回路或系统级设备方案；不在本期添加 Root、Hook、无障碍自动按住微信、自动发送消息。

本期必须完成：云端 P0 修复、本地音频 P0 修复、悬浮文件播放器、倒计时外放、真机兼容性报告。悬浮窗内实时推理/编辑长文本、自动 ASR、实时变声和微信通话变声不纳入首期。

## 2. 项目现状与证据

技术栈：原生 Android / Kotlin / Jetpack Compose，Room、DataStore、OkHttp；`minSdk=24`、`targetSdk=36`、`compileSdk=36`；本地使用 sherpa-onnx `1.13.8` AAR、ZipVoice-Distill INT8 与 Vocos 24 kHz。保留当前 Provider 架构，不重写整个应用。

下面的文件路径均相对于仓库根；函数名为定位依据，后续行号变动不影响任务。

| 已确认现象或代码事实 | 位置 | 对建设的影响 |
| --- | --- | --- |
| 截图显示“连接失败：无法读取云端音色列表”；标题和清除按钮被挤成多行 | `ui/SettingsScreen.kt` 的云端卡片 | 接口诊断与响应式布局都需修复 |
| `listVoices()` 只查询 `voice_cloning`；`validateConfig()` 使用它验证整个云服务 | `provider/minimax/MiniMaxApiClient.kt`、`MiniMaxProvider.kt` | 首次使用缺少官方音色入口；目录查询与合成能力耦合 |
| `parseClonedVoices()` 对合法空列表返回成功 | `MiniMaxApiClient.kt` | **空克隆列表本身不是截图报错的已证实原因**，不能只改空列表处理就宣布修复 |
| 非 2xx 只读取 HTTP 状态；未知异常退化为笼统文案；缺少 `base_resp` 时可能按成功处理 | 同上 | 丢失诊断线索，且 `{}` 可能被误判为连接成功 |
| API Key 保存时未规范化首尾空白；区域切换立即落盘；测试连接也会保存表单 | `data/settings/MiniMaxConfig.kt`、`ui/MainViewModel.kt` | 存在粘贴字符、区域与凭据混用、测试意外覆盖设置风险 |
| 音色列表失败被 `getOrDefault(emptyList())` 吞掉 | `MiniMaxProvider.getVoices()` | 无法区分没有音色、网络失败和授权失败 |
| 解密失败被转换成 `null` | `MiniMaxConfig.apiKey()` | 密钥损坏与尚未配置无法区分 |
| `MediaCodec` 在 configure/start 前获取 inputBuffers/outputBuffers | `core/audio/AudioCodecDecoder.kt` | 不符合生命周期；压缩音频导入可失败；不是直接录音路径的已证实根因 |
| 解码只更新输出采样率，未按实际输出声道数与 PCM 编码解析 | 同上 | Float PCM、多声道等存在误读风险 |
| 录音线程与 stop 调用端均可能 stop/release；join 3 秒后仍可能写文件；调用端依据旧文件长度判断成功 | `core/audio/VoiceRecorder.kt` | 旧文件冒充新录音、半成品与资源竞态风险 |
| UI 直接同步调用 recorder.stop() | `ui/VoicesScreen.kt` | join 可阻塞主线程 |
| WAV writer 没有截断旧文件；reader 对多声道、chunk 边界和对齐处理不足 | `core/audio/PcmAudio.kt` | 同名短录音覆盖长录音可能留尾部旧字节；畸形文件风险 |
| 降采样只有线性插值 | `PcmAudio.kt` 的 Resampler | 缺乏明确抗混叠滤波；是否导致本例杂音仍需对照 |
| 大于 1 倍速采用生成后重采样式加速 | `core/audio/AudioSpeedScaler.kt`、`SherpaZipVoiceProvider.kt` | 会改变音高；应与文件损坏、电流声区分 |
| referenceStatus 只查文件存在与文本非空，录音替换时保留原参考文本 | `domain/voice/VoiceProfileManager.kt`、`VoicesScreen.kt` | 音文不一致仍可能进入推理 |
| 当前播放器依附 Activity/Compose，同步 prepare，无独立后台播放服务 | `core/storage/AudioPlaybackController.kt`、`ui/ShineVoiceRoot.kt` | 不适合跨应用可靠播放 |
| SPEAKER 分支直接返回 true，没有验证实际输出设备 | `core/audio/AudioRouteManager.kt` | 蓝牙/耳机连接时“外放”不一定走扬声器 |
| Manifest 没有悬浮权限、前台服务权限及对应服务 | `app/src/main/AndroidManifest.xml` | 悬浮播放需建设完整权限和生命周期 |

尚未确认：用户 Key 所属区域及账号权限、真实 HTTP/业务错误、故障 WAV 的结构、录音原始音质、用户手机与微信版本。不能从截图推断 Key 无效，也不能把全部噪声归因于某一个源码缺陷。历史 README 和代码注释中的“真实验收”“native bug”等描述应视为已有记录，后续仍需检查对应证据与本机复现。

## 3. 总体实施顺序

1. M0：保留用户数据与故障样本，建立脱敏诊断；在用户手机做最小微信外放可行性试验。
2. M1：MiniMax 请求契约、设置状态、错误分类、官方音色闭环和布局修复。
3. M2：录音/导入/标准化/推理/保存/播放逐段定位与修复。
4. M3：把播放所有权迁至 Service，实现悬浮按钮、文件选择与倒计时。
5. M4：真机微信收录、回归测试、升级迁移和发布材料。

M0 的微信试验应尽早做，防止完整开发后才发现用户手机无法收录。若不可行，仍完成已确定可用的悬浮播放器与音频修复，并明确把“微信收录”标记为受阻，不能用文件分享替代验收后宣称三个目标全部完成。

## 4. 云端高清修复设计

### 4.1 配置与状态模型

增加不可变 `MiniMaxConfigSnapshot(region, baseUrl, apiKey, legacyGroupId, revision)`，同一次请求使用同一快照；禁止分别读取多个 Flow 造成区域、Key 与 Group ID 跨版本组合。

- 大陆与国际继续使用现有域名配置；国际当前文档确认 `https://api.minimax.io/v1/get_voice`。大陆域名沿用 `https://api.minimaxi.com`，本次大陆文档页面未成功获取，实现阶段需在对应控制台核验，不根据国际账号推断大陆权限。
- Key 输入仅去首尾空白；检测换行、零宽字符、误粘贴 `Bearer ` 等，明确提示修正，不在 Key 中间静默删改字符；不硬编码某一种 Key 前缀为唯一合法规则。
- 区域选择先改草稿，不立即覆盖持久化配置；保存将区域、密钥、Group ID 原子写入 DataStore。
- “保存并测试”先说明保存成功与否，再展示测试结果；网络失败不等于保存失败。“测试当前配置”验证草稿，不能暗中保存。
- 未编辑密钥时保留原密钥，以 `hasStoredKey` 与输入草稿区分；不要把星号字符串当密钥。解密失败单独展示“本机密钥无法读取，请重新填写”。
- 保留 Keystore 加密；不把 Key 放日志、截图、URL、异常堆栈附带请求头或测试命令行。兼容已有 DataStore 数据，不自动清空。
- Group ID 放进“高级兼容设置”，默认为空；没有证据时不要求用户填写，更不写随机值。构建 URL 使用 OkHttp `HttpUrl`，参数正规编码。
- 同时点击测试只运行一个任务；新 revision 使旧结果失效；清除配置取消请求并使在途回调无权恢复旧状态。协程取消不能被 `runCatching` 吞掉。

建议状态：`Unconfigured / SavedUntested / Testing / Reachable / Failed`。另存 `catalogState`、`lastSynthesisCheck` 与 `cloneCapability`，不再以一个字符串兼任网络、鉴权和合成可用性。

### 4.2 分阶段连接验证

官方 Get Voice 支持按类别取目录，包含系统音色与克隆音色；部分克隆音色首次使用前不出现在查询结果中。因此克隆后必须保留返回/请求的 voice_id，不能因列表暂未出现就判定失败。[MiniMax Get Voice](https://platform.minimax.io/docs/api-reference/voice-management-get)

1. 本地校验草稿与加密读写，不发网络请求。
2. 查询系统音色 `POST /v1/get_voice`、`voice_type=system`；实现前再次核对对应区域枚举。也可查询 `all` 后分组，但不能只依赖克隆权限。
3. `HTTP 2xx + 合法业务成功结构` 才是目录请求成功；合法空集合是空状态。返回 HTML、空体、非法 JSON、缺关键契约字段要标记协议错误，禁止 `{}` 显示 ONLINE。
4. 查询克隆音色作为独立能力。失败显示“官方音色可用；自定义音色暂不可读取”，不把已经通过的合成能力全局禁用。
5. 用户点击“生成测试语音”才用所选官方 voice_id 合成短句，界面说明会调用计费接口。不在启动/重组/每次连接检查时自动合成。
6. 合成返回后通过统一音频校验，允许播放和导出；此时才展示“已通过语音生成测试”。目录成功不代表余额、模型或克隆权限已通过。

在创作页提供官方音色选择，不要求先付费克隆才能第一次使用云端。官方 voice_id 以目录结果为准，不拍脑袋写入固定 ID。

### 4.3 HTTP 与业务错误处理

将 transport、business、parse、storage 四层错误分开。建议 `CloudDiagnostic(operation, host, httpStatus, businessCode, safeMessage, requestId, elapsedMs, retryable)`；原始响应只在内存有限长度解析，不默认落盘。

| 情况 | 用户信息 | 重试策略 |
| --- | --- | --- |
| DNS/离线 | 无法连接服务，检查网络及服务区域 | 允许手动重试 |
| TLS/证书 | 安全连接失败，检查设备时间或网络代理 | 不关闭证书校验 |
| 连接/读取超时 | 服务响应超时 | 查询可有限退避 |
| HTTP 401/403、鉴权业务码 | Key 无效、区域不匹配或服务无权限；具体按响应 | 修改配置后重试 |
| 余额不足 | 账号余额不足 | 不自动重试 |
| 429/限流业务码 | 请求频繁，稍后重试 | 尊重 Retry-After，有上限 |
| 400/422/参数业务码 | 当前请求参数不受支持 | 展示脱敏参数名 |
| 5xx | 服务暂时异常 | 只读调用有限退避 |
| 2xx + 非零 base_resp | 展示业务原因 | 按业务码分类 |
| HTML/空体/格式错误 | 服务响应格式异常 | 显示诊断编号 |

已核对官方通用错误表：`1004` 鉴权、`1008` 余额不足、`1002` 限流、`1001` 超时；其他现有映射应逐一核对端点文档，不能照搬代码注释作为依据。[错误表](https://platform.minimax.io/docs/api-reference/errorcode)

增加余额不足、权限不足、协议错误等领域错误码；非 2xx 也尝试有限读取业务错误，再回退 HTTP 分类。所有 status_msg 先脱敏、截长，禁止把服务器正文直接塞满状态徽标。

上传、克隆、合成为可能产生费用或远端状态的请求，超时后不得盲目自动重放；在结果未知时保留任务标识，提示先查询或由用户决定再次提交。OkHttp Call 必须跟随取消，设置 call 总时限；异步回调桥接协程，避免不可取消的 execute 阻塞长期占用。

### 4.4 合成与克隆完整性

保留 `POST /v1/t2a_v2` 非流式方案；现有 `speech-2.8-hd` 不应在未验证前随意替换。使用 `output_format=hex` 时按十六进制解码，每个字符必须有效；现有 `Character.digit=-1` 未拒绝的情况要修复。WAV 参数与响应格式以对应区域当前契约为准。[HTTP TTS](https://platform.minimax.io/docs/api-reference/speech-t2a-http)

- 不通过改扩展名把 MP3/裸 PCM 伪装 WAV。先识别实际内容，必要时解码转统一格式。
- URL 响应只按官方已核验的 HTTPS 下载规则处理，不向下载主机转发 Bearer；限制大小、跳转与超时，拒绝空文件/HTML。
- 写 `.part`，验证可解码、非空、时长合理后原子提交；失败移除半成品，不写成功历史。
- 上传前独立校验当前云端时长/体积/格式限制，不复用本地参考音频长度建议。克隆成功保存 voice_id，并关联当前账号配置身份与区域。
- 切换账号或区域使旧云绑定待确认，不删除本地录音；避免新账号误用旧 voice_id。首次合成激活后再刷新目录；克隆列表缺项不自动重建收费音色。

### 4.5 设置页布局

- 标题与短状态同一行只放“未配置/测试中/可连接/失败”；错误详情独立整行，可换行与展开脱敏诊断。
- 主按钮“保存并测试”独占一行；“测试当前配置”“清除配置”下一行，窄屏/大字体时上下排列。
- Group ID 收起到高级区；Key 提供显示/隐藏按钮，默认隐藏。
- 触控目标至少 48dp；不使用缩小字体来掩盖挤压；测试 320/360/411dp 宽度、1.0/1.3/2.0 字体和横屏。
- 相同的标题/长状态布局修复复用到系统语音卡片，避免截图下方“切换中”再次挤坏标题。

## 5. 本地录音音色与杂音修复

### 5.1 先隔离问题所在环节

建立同一份短文本与参考录音的证据链：

`原始录音 → 标准化 reference.wav → JNI 输入浮点数据 → native 原始输出 → 变速后输出 → 最终 WAV → 应用播放/系统播放器播放`

每段记录时长、采样率、声道、编码、字节数、peak、RMS、DC offset、削波比例、非有限值数量和 SHA-256。保留 A/B 音频由人试听；仅看 RMS、文件大小或识别文本不能证明没有杂音。

| 对照 | 判断 |
| --- | --- |
| 参考音频本身有噪声 | 先查录音源、设备路由、削波和预处理 |
| 参考音频正常，生成文件在多个播放器均异常 | 查音文匹配、推理输入、模型、步数、浮点数据及保存 |
| 导出正常，应用内异常 | 查播放器、音频焦点、通信模式及路由 |
| 1.0 倍正常，1.5/2.0 倍异常 | 查变速后处理，不先修改录音链路 |
| 官方示例正常，自录失败 | 查录音质量、文本一致性和标准化 |
| 官方示例也失败 | 查 AAR/ABI/模型哈希、runtime 参数及输出存储 |

故障样本是用户数据，默认只在应用私有诊断目录短期保留；音频不提交 Git，不自动上传云端。已有历史的损坏音频标记为异常，提供重新生成，不能伪装修复已有文件。

### 5.2 录音器重构

新增 `RecordingSession`，由 ViewModel/领域层管理，不由 Composable 持有底层录音资源。状态：`Idle → Preparing → Recording → Stopping → Processing → Ready/Error`。

1. 使用设备实际支持的单声道 PCM16 采样率；优先测试 48k/44.1k，失败再按能力回退。记录实际采样率，不仅改 WAV 头。
2. 在应用前台申请 RECORD_AUDIO；开始录音先停止本应用试听；本期不录后台麦克风，离开录音页面时明确停止或取消。
3. 录音源先保留 MIC 基线，对比支持的 VOICE_RECOGNITION/UNPROCESSED；后者需检查支持情况。不要无证据开启多层 AGC/降噪。
4. 在工作线程 start/read；处理初始化失败、SecurityException、所有负 read 返回值与持续零读取；错误进入 Error，防止空转。
5. 分块写临时 PCM，避免 `MutableList<Short>` 装箱与无限增长；本地录音默认建议 6–20 秒，首期录音硬上限 60 秒。这是产品建议，不宣称为模型或云端硬限制。
6. stop 为 suspend：发停止信号，按可控顺序解除阻塞读取，等待工作任务结束；仅一个资源所有者 release；禁止 UI join 与超时后双重 release。
7. 本次 session 写入唯一临时路径；标准化和校验完成才替换 reference。旧文件不能参与本次成功判断。
8. 所有失败路径 finally 清理；文件失败保留上一份有效 reference；取消不会替换音色。

UI 增加录音时长、音量表、停止后的处理中状态和参考录音试听。重新录音后参考文本标记“需确认与新录音一致”；默认展示固定朗读短文辅助录制。用户确认文本与音频一致后才能本地克隆。

### 5.3 统一标准化管线

增加 `AudioNormalizer`：录音与导入走同一出口，输出单声道、24,000 Hz、PCM signed 16-bit little-endian WAV。原始音频可保留用于重新处理，写入 `sourceAudioPath`；不要多次重复重采样。

修复 MediaCodec 顺序为 create → configure → start → dequeue/getInputBuffer/getOutputBuffer；按 `BufferInfo.offset/size` 读有效字节，输出格式变化时读取实际采样率、声道数和 PCM encoding；PCM16/PCM_FLOAT 分支转换，不支持的格式明确拒绝。资源在 finally 释放，设置总时间和无进展超时，传播取消。[MediaCodec 生命周期](https://developer.android.com/reference/android/media/MediaCodec)

WAV 解析与写入要求：

- 支持的 PCM16 单/双声道按完整 frame 读取；多声道要么正确消费所有声道并下混，要么明确拒绝，不能只读左右两个后把剩余数据当下一帧。
- 检查 RIFF/fmt/data、chunk 的偶数字节 padding、块长度、blockAlign、byteRate、文件实际边界与整帧对齐；拒绝负长度、溢出和截断。
- 对 float/extensible 等格式仅在经过验证的解码器支持时转换，否则清楚报不支持。
- writer 截断目标或使用全新临时文件，写完校验 header 长度与实际长度；父目录存在；失败不替换有效文件。
- 降采样使用有抗混叠滤波的实现，先评估当前 AAR 可用 resampler；否则使用有维护记录且许可兼容的实现，固定版本。无需为修录音引入整套 FFmpeg。

质量检测：空/全静音、NaN/Inf、坏格式直接拒绝；过轻、明显削波、长静音给重录建议。初始警戒参考为 peak > 0.99 且削波样本 > 0.5%、RMS < -45 dBFS；这些是可调工程阈值，不是人声质量或信噪比的充分判据。不要将底噪强制拉到大音量，也不要用限幅掩盖数据类型错误。

### 5.4 推理输入与输出

- 继续使用 `OfflineTts.generateWithConfig`。官方示例传参考波形、真实参考采样率、参考转写，Distill 示例为 4 步；先建立 speed=1.0、numSteps=4 的质量基线，再比较其他参数。[ZipVoice 官方示例](https://github.com/k2-fsa/sherpa-onnx/blob/master/python-api-examples/zipvoice-tts-play.py)
- 核对项目固定的 AAR 1.13.8 对应源码/API，不把 master 新字段直接搬入。保留模型哈希、单线程及每请求回收的现有兼容策略，未经复现不移除。
- JNI 输入检查参考样本范围、有效性、采样率、音文匹配标记；Float 应为归一化幅度，不把 PCM16 字节直接 reinterpret 成 Float。
- 在 native 输出、变速输出、文件重读三处比较数据；严禁 NaN/Inf 进入存储，严重异常返回失败。
- 文件写入成功且重新解码通过后才提交成功历史。浮点转 PCM16 时显式饱和转换；记录削波，不做整数回绕。
- 明确当前 speed>1 通过重采样会提高音高；优先验证变调是否就是用户所谓“杂音”。若要保持音高，引入经过许可核验的 time-stretch（如 WSOLA 类实现），对短句和高倍速单独测试；不能解除现有 native 快速档保护来“修音质”。
- 本地推理与 release 串行化；同步 JNI 无法真正中断时，取消只丢弃结果，不并发释放仍使用中的 runtime，不允许下一任务抢入。

## 6. 悬浮按钮与后台播放设计

### 6.1 用户操作流程

1. 在创作/历史页生成并试听正常的音频，点“加入悬浮播放”。音频先完整落盘，跨应用场景不等待推理。
2. 首次启用解释悬浮权限，跳系统授权页；返回重新检查 `Settings.canDrawOverlays()`，通过后显示可拖拽吸边圆钮。
3. 切到微信，点圆钮展开：当前音频、最近条目、播放/停止、延时 0/2/3/5 秒、关闭；默认 3 秒。
4. 选择“倒计时播放”后面板收起，显示数字提示；用户随后在微信按住说话。
5. 到时播放，用户继续按住微信直到结束，然后由用户决定是否发送。倒计时不发语音提示音，避免被录入。
6. 正在倒计时再次点击为取消；播放中主按钮为停止；关闭悬浮模式终止倒计时和悬浮会话播放。

这是单次外放辅助，不自动检测微信是否正在录音，不自动操作微信。用户已经按住微信再去点悬浮窗可能打断手势，因此默认引导先倒计时再按住。超过目标应用单条录音限制时提醒用户分段，限制通过当前版本实际验证，不写死传闻中的秒数。

### 6.2 架构与所有权

建议新增：

```text
core/playback/
  PlaybackCoordinator.kt         # 所有播放入口统一命令与 StateFlow
  PlaybackState.kt               # itemId / position / duration / route / error
  PlaybackService.kt             # Media3 MediaSessionService 与唯一播放器
  PlaybackQueueRepository.kt     # 从现有成功历史取媒体，管理当前条目
feature/overlay/
  OverlayControllerService.kt    # 用户显式开启的跨应用可见控制会话
  OverlayWindowController.kt     # WindowManager、拖拽、位置、窗口释放
  OverlayPanelView.kt            # 简单原生 View 或带完整 owner 的 ComposeView
  OverlayPermissionCoordinator.kt
  DelayedPlaybackController.kt   # 单调时钟、取消令牌、单次触发
data/settings/OverlaySettings.kt
```

优先 Media3 ExoPlayer + MediaSessionService，固定兼容本项目 minSdk 的稳定版本。页面、悬浮窗和通知都控制同一个播放器，不各 new 一个 MediaPlayer。官方把 MediaSessionService 作为后台播放入口；播放生命周期由 Service 持有。[MediaSessionService](https://developer.android.com/reference/androidx/media3/session/MediaSessionService)

`AudioPlaybackController` 先改为适配门面迁移现有调用者；Activity 销毁只解绑控制器，不 release 后台 player。页面播放状态改为订阅 Service 状态，不能由多个 UI 回调各自维护互相冲突的 nowPlaying。

命令示意：`Select(historyId)`、`Play(historyId)`、`Schedule(historyId, delayMs)`、`Stop`、`CloseOverlay`。业务只收内部媒体 ID，仓库解析应用拥有的文件，拒绝任意外部路径。Service 默认不导出；若系统媒体接入要求导出，MediaSession 明确限制控制器身份与可执行命令。

### 6.3 权限、FGS 与常驻策略

新增 `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`；Android 13+ 按实际通知需要处理 `POST_NOTIFICATIONS`。系统媒体通知的豁免与普通悬浮会话通知分别处理；拒绝通知不能简单等同于禁止所有前台服务。

**必须区分待机悬浮窗与正在播放：不能无限播放静音，也不能仅为常驻悬浮按钮长期冒用 mediaPlayback。** 建议采用两个职责分开的 Service：

- `PlaybackService`：真实播放时使用 `mediaPlayback` 类型，由 Media3 管理媒体通知和播放生命周期。
- `OverlayControllerService`：用户主动开启且可见的跨应用控制会话。API 34+ 拟用 `specialUse`，声明 `FOREGROUND_SERVICE_SPECIAL_USE` 和 `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，具体说明“用户启动的跨应用音频控制悬浮窗与延时播放控制”。实现前依据实际发行渠道核对适用性；该类型不是任意后台保活通行证。若发行审核不接受，改成有限时长的可见会话/通知入口恢复，不能伪报其他服务类型。[FGS 类型与 specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types)

Manifest 设置 overlay service 非导出；两个服务用途和通知渠道清楚，通知提供停止/关闭。API 26 以下按版本分支启动普通 Service；API 26+ 用 startForegroundService 并及时进入前台；只有当前真实执行的类型才传给 startForeground。

优先在 Activity 可见、用户点击开启时启动会话。Android 15+ 依赖悬浮权限从后台启动 FGS 时，还需要已经可见的悬浮窗；不能先从后台启动服务再创建窗来满足条件。[Android 15 限制](https://developer.android.com/about/versions/15/behavior-changes-15)

不做开机自启、不在用户强停后复活。锁屏取消倒计时并隐藏浮窗；来电/音频中断停止本次微信辅助播放，恢复后不自动重播。进程死亡不恢复在途倒计时，不自动突然发声；用户从应用或通知重新开启。遇到启动受限返回可操作状态，不崩溃。

### 6.4 窗口交互

- API 26+ 使用 TYPE_APPLICATION_OVERLAY；API 24–25 明确实现合法旧窗口分支并实测，或只对这两个版本禁用悬浮、保留其他功能，不能调用不存在的新 API 后崩溃。
- 窗口使用 WRAP_CONTENT/真实内容边界，默认 FLAG_NOT_FOCUSABLE；仅自身区域消费触摸，不建立透明全屏覆盖层，不试图向微信注入触摸事件。
- 圆钮触控至少 48dp，拖拽使用 touch slop 区分点击；位置按可用区域比例持久化，旋转、刘海、导航栏和键盘变化后 clamp 到屏内。
- 收起后不抢输入焦点；展开仅用于选择已生成文件，首期无需键盘输入。
- 权限撤销、onDestroy、重复关闭都幂等 removeView；捕获 BadToken/SecurityException 并更新状态；ComposeView 若使用，必须提供 LifecycleOwner/SavedStateRegistryOwner 并销毁 composition。
- 微信可能禁止/隐藏覆盖窗，或系统阻止被遮挡触摸；只记录不兼容并提供应用内/通知控制，不绕过。

### 6.5 焦点、路由与延时状态

默认媒体属性 `USAGE_MEDIA + CONTENT_TYPE_SPEECH`，只播放已校验文件。音频焦点走统一策略，避免 Media3 自动处理和手动 request 同时生效。Android 15+ 请求焦点需要前台应用或前台服务，先保证合法播放生命周期。[音频焦点](https://developer.android.com/media/optimize/audio-focus)

- 失去焦点停止或暂停；微信辅助场景一旦中断，取消本轮且不自动续播，以免错过用户按住说话时机。
- 不循环抢焦点、不用闹钟流/通话流强行绕过系统静音、不抢占微信麦克风；不得假设“申请 transient 焦点”必然与微信兼容。
- 外放辅助模式禁用本应用听筒模式；不要在微信录音时反复设置 MODE_IN_COMMUNICATION 或 MODE_NORMAL。现有 AudioRouteManager 的通信模式撤销必须考虑所有权，不重置其他应用后来接管的模式。
- 系统媒体路由与“强制扬声器”区分：首期要求断开蓝牙/有线耳机用于外放收音。若提供 preferredDevice，只能作为请求，读取实际路由确认；失败提示，不假报成功。
- 不自动把系统音量拉满；先给音量试听，用户自行调节。耳机拔插、路由突然变化时中止倒计时或播放，避免意外外放。
- 倒计时使用 elapsedRealtime 与可取消 Job，仅在内存；预载文件后起计时，开始时再次校验权限、文件、会话 token 和路由。重复调度替换旧任务，永远只播一次。
- 屏幕保持唤醒不应全局常驻；短倒计时仅在用户交互会话内，锁屏直接取消，无需精确闹钟权限。

## 7. 数据、兼容与逐文件任务

当前 Room version=3。首期悬浮列表直接引用成功历史，不复制大文件；收藏如确需持久化新增独立表；仅偏好、位置、延时放 DataStore。

音色质量元数据建议新增：`referenceRevision`、`confirmedTextRevision`、`normalizationVersion`、`qualityStatus`；可以建独立表避免污染 Provider 绑定。云绑定增加区域和账号配置身份，账号身份使用随机配置 ID/版本，不用明文 Key，也不对外展示 Key 哈希。

若改 Room，提供 3→4 及旧路径链式迁移、schema 导出和迁移测试；不使用 destructive migration。旧音色标为待校验，首次使用后台校验；缺少原始文件时不伪造 sourceAudioPath，不覆盖唯一参考录音。重新处理生成新文件，校验成功才切换引用。

删除当前播放或倒计时引用的历史时先协调停止并清除队列指针，再删除文件与记录。文件提交后数据库失败应回滚新文件；启动时清理孤立 `.part`，不误删有效 WAV。

| 文件/目录 | 必须执行的任务 |
| --- | --- |
| `provider/minimax/MiniMaxApiClient.kt` | 可注入 HTTP client；目录分类、响应契约、脱敏诊断、取消、严格 hex、原子文件输出 |
| `provider/minimax/MiniMaxProvider.kt` | 目录与鉴权/合成能力分离；官方音色入口；不吞目录错误；克隆 ID 激活逻辑 |
| `data/settings/MiniMaxConfig.kt` | 快照、草稿/保存分离、密钥读取错误、区域/账号版本 |
| `domain/tts/TtsModels.kt` | 增加准确的云端/音频错误分类，保持原调用者兼容 |
| `ui/MainViewModel.kt` | 测试互斥、旧请求失效、清除取消、云测试状态、统一播放绑定 |
| `ui/SettingsScreen.kt`、`ui/cyber/CyberComponents.kt` | 修复长状态与按钮布局；权限入口、短状态与详情分离 |
| `core/audio/VoiceRecorder.kt`、`ui/VoicesScreen.kt` | 生命周期、异步 stop、单所有者资源、新录音原子替换、文本确认 |
| `core/audio/AudioCodecDecoder.kt`、`PcmAudio.kt`、`ReferenceAudioImporter.kt` | 解码时序、输出格式、WAV 边界、抗混叠、共享标准化 |
| `core/storage/ReferenceAudioLoader.kt` | JNI 输入校验和质量元数据 |
| `provider/sherpa/SherpaRuntimeManager.kt`、`SherpaZipVoiceProvider.kt` | 基线对照、输出检查、串行释放、保存后复读 |
| `core/audio/AudioSpeedScaler.kt` | 明确当前变调行为，按证据决定保音高替代，不移除 native 保护 |
| `core/storage/AudioPlaybackController.kt`、`core/audio/AudioRouteManager.kt` | Service 适配、焦点统一、真实设备与通信模式所有权 |
| `ui/HistoryScreen.kt`、`ui/CreateScreen.kt`、`ui/ShineVoiceRoot.kt`、`MainActivity.kt` | 加入悬浮队列、共享播放状态、Activity 解绑不终止后台会话 |
| 新 `core/playback/` 与 `feature/overlay/` | 实现第 6 节全部模块 |
| `ShineVoiceApplication.kt` | 注入共享仓库/协调器，不持有 Activity；避免多播放器 |
| `data/db/`、`data/settings/` | 元数据、偏好、迁移与清理 |
| Manifest、`app/build.gradle` | 权限、服务类型、Media3 与测试依赖固定版本 |
| `app/src/test/`、`app/src/androidTest/`、`docs/testing/` | 自动化测试、真机证据、兼容矩阵 |

## 8. 验证计划与完成标准

### 8.1 云端测试

在现有 MiniMaxApiClientTest 之外加入可注入 MockWebServer 的端到端请求测试，不只测试 JSON 工具函数。

| 编号 | 条件 | 预期 |
| --- | --- | --- |
| C01 | 有效账号、无克隆音色 | 系统目录成功；能选择官方音色并生成 |
| C02 | 无效 Key / 区域不匹配 | 明确鉴权/区域线索，不出现笼统 Unknown |
| C03 | 目录成功但余额不足 | 保留可连接状态，生成显示余额原因 |
| C04 | 克隆权限失败 | 官方音色可用，不全局禁用 |
| C05 | HTTP200 业务失败、401 JSON、502 HTML、空体 | 分类正确，绝不假 ONLINE |
| C06 | 合法空列表与缺关键字段 | 前者为空状态，后者协议错误 |
| C07 | 快速切区域/重复测试/清除配置 | 旧回调不能覆盖新配置和状态 |
| C08 | Key 首尾空白、内部非法字符、密钥解密失败 | 规范化或明确提示，日志无凭据 |
| C09 | 非法 hex/空音频/MP3 冒充 WAV/下载中断 | 不能保存为成功音频 |
| C10 | 克隆刚成功但目录暂未出现 | 保存 ID，可首次合成，不重复收费克隆 |

真实云端验收至少一个用户实际使用区域的账号，完成“保存→重启→读取配置→官方音色生成→播放→导出”；克隆另做完整闭环。没有合法测试 Key 时继续完成 mock/本地部分，真实云测试明确标记待验证，不把 mock 成功写成云端可用。凭据由应用 UI 输入或安全测试注入，不能复制到报告和 shell 历史。

### 8.2 音频测试

- WAV：长文件覆盖短文件、奇数 chunk padding、多 data chunk、截断、极大伪造长度、PCM16 单双声道、多声道拒绝/正确转换。
- 解码：MP3、AAC/M4A、实际输出格式变化、PCM_FLOAT fixture、非法文件、取消与超时；MediaCodec 需要 instrumented 测试，不以 JVM mock 替代。
- 重采样：44.1k/48k→24k，1 kHz 正弦频率误差目标 <1%，幅度误差 <1 dB；增加 12 kHz 以上输入的混叠抑制测试，目标与所选滤波器规格一起提交。
- 录音：连续启停 20 次、极短录音、权限撤销、麦克风占用、离开页面、屏幕旋转、存储失败；无旧文件伪成功、无卡主线程、无资源泄漏。
- 本地生成：官方参考 + 自录中文 + 自录英文，各至少 3 个长短句；1.0 倍基线通过后再测 0.75/1.25/1.5/2.0 倍，检查音高变化与杂音分别记分。
- 相同输入连续生成 20 次：20/20 可解码、无崩溃、无 NaN/Inf、无新增截断；内存趋势留记录。非确定性模型不要求字节完全相同。
- 质量门槛：应用内、系统播放器、导出文件三者均可播放；用户问题样本没有持续电流声/爆音；文字完整可懂，首尾不丢。由至少两名试听者或用户+开发者记录结论，不能仅靠 ASR 判定。

### 8.3 悬浮与微信真机矩阵

必测用户实际手机；再测至少一个不同 ROM 的物理设备，系统覆盖 Android 13 与 Android 15/16 中至少两个版本。API24/25、26、31、34、35、36 的权限/窗口/服务分支可用模拟器补充；模拟器不能替代微信真实收音验证。

| 编号 | 操作 | 验收 |
| --- | --- | --- |
| O01 | 拒绝授权、授权后返回、撤销权限 | 状态准确，无崩溃/残窗 |
| O02 | 微信前台拖拽、吸边、展开、收起 | 不挡按住说话区域，不抢输入焦点 |
| O03 | 点倒计时后按住微信说话 | 若设备支持，首尾完整收录，用户自己发送 |
| O04 | 取消倒计时、连续点、多次选文件 | 只播放最后一次有效任务，无延迟幽灵播放 |
| O05 | 离开 ShineVoice / Activity 重建 | 文件继续播放，状态一致 |
| O06 | 通知停止、关闭悬浮、锁屏、来电 | 按定义停止/取消，之后不自动重播 |
| O07 | 蓝牙/有线耳机/断开设备 | 实际路由准确，变化中断且不突然外放 |
| O08 | 用户删除当前文件、清理存储 | 停止引用，错误可恢复 |
| O09 | 进程被杀、强停、重启手机 | 不自动出声；重新进入可恢复选择 |
| O10 | 微信抢焦点或过滤本机外放 | 记录不支持/不稳定，不绕过焦点策略 |
| O11 | 10 分钟待机悬浮、连续 20 次播放 | 通知与 FGS 类型正确，无 ANR/泄漏/后台启动异常 |

微信测试使用自有测试会话，由用户决定发送；记录手机型号、Android/ROM、微信版本、输出路由、媒体音量档位、延时、录音长度、是否可懂、是否丢首尾。至少 10 次标准短句：目标设备 10/10 成功才标“该配置已验证”。若仅部分成功，标不稳定并记录比例，不写“支持所有微信”。

两项结果分别报告：A 悬浮播放功能通过；B 微信收录效果通过/不稳定/不支持。B 不通过时提供可选的“分享音频文件”或第二台设备外放，但明确文件分享不是微信原生语音气泡，也不是同机麦克风注入。

### 8.4 构建与升级

实施时根据仓库环境准备固定 AAR 和模型；以下命令为待执行清单，本次文档编写未运行构建：

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

在测试设备或测试数据副本做旧版→新版覆盖升级：历史、当前音色、参考录音、区域配置和密钥可用；不得卸载用户正式包来替代升级测试。Debug 包名为 `com.shinevoice.debug`，release 为 `com.shinevoice`，验证时分别记录。发布继续沿用现有签名与更新协议，不在方案实施中临时换证书。

## 9. 实施里程碑与验收产物

| 阶段 | 产物 | 进入下一阶段的标准 |
| --- | --- | --- |
| M0 诊断 | `docs/testing/cloud-audio-baseline.md`、故障复现步骤、微信初步可行性记录 | 已知事实与待验证项分清，有录音/响应定位方法 |
| M1 云端 | 配置快照、准确错误、官方音色、布局与测试 | C01–C10 自动化通过；真实云结果单列 |
| M2 音频 | 标准化/录音修复、前后对比样本、指标、听感记录 | 原始问题复现后消失，20 次稳定生成通过 |
| M3 悬浮 | 服务、通知、倒计时、队列与权限引导 | O01–O09/O11 技术验收通过 |
| M4 收尾 | `docs/testing/overlay-wechat-compatibility.md`、迁移报告、回归结果、APK 信息 | 目标手机微信结果明确；所有未测项显式列出 |

提交按云端、音频、播放器迁移、悬浮交互、验收文档拆成可审查变更；每次保留回退路径。不要为修复三个问题同时升级所有依赖、改 UI 风格或更换模型。

最终交付至少包括：变更代码、通过的测试命令与结果、失败/未测项、云端脱敏错误样本、音频前后试听记录、悬浮录屏/截图、微信兼容矩阵、数据库迁移证据、构建 APK 路径与 SHA-256。没有真机/Key 时相关门槛保持未完成，报告具体缺少的条件。

## 10. 可直接发给实施 agent 的任务说明

> 请在 ShineVoice 当前工作区实现本方案。先阅读现有代码和本文件，以用户的三个目标为验收主线：MiniMax 真正可配置使用、自录音色本地合成无明显杂音、跨应用悬浮按钮播放并验证微信外放收录。保留用户已有修改与音色/历史数据。
>
> 先完成 M0 诊断，区分源码已确认缺陷和真实故障根因；不要只修改提示文案或将空音色列表判成功。云端需要官方音色与克隆音色独立闭环；本地需保留输入/输出对照定位噪声；悬浮需要 Service、权限、音频焦点、倒计时、实际路由与生命周期。
>
> 依 M1→M4 实施，运行相应自动化与实机验证，提交可复查证据。普通安卓没有向微信注入麦克风音频的通用接口，不得虚构该能力；按本文验证外放收音并如实标记兼容性。缺 Key、故障样本或物理设备时完成所有独立可做工作，清楚报告哪些真实验收尚未通过。不要用 mock、能编译或文件存在替代端到端验收。

## 11. 官方依据与复核要求

本次核查日期为 2026-09-25；API、ROM 和微信行为会变化。以下为可复核的一手来源，实施阶段需再次核对具体版本。大陆 MiniMax 文档抓取未成功，不能把国际站结果当作大陆账号实测。

- [MiniMax Get Voice](https://platform.minimax.io/docs/api-reference/voice-management-get)：目录类别、鉴权及克隆音色首次使用说明。
- [MiniMax HTTP TTS](https://platform.minimax.io/docs/api-reference/speech-t2a-http)：合成端点与音频返回契约。
- [MiniMax 错误码](https://platform.minimax.io/docs/api-reference/errorcode)：通用错误分类。
- [sherpa ZipVoice](https://k2-fsa.github.io/sherpa/onnx/tts/zipvoice.html)、[官方生成示例](https://github.com/k2-fsa/sherpa-onnx/blob/master/python-api-examples/zipvoice-tts-play.py)：模型入口与参考音频/文本用法；项目实现须对齐固定 1.13.8。
- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)：解码生命周期与输出格式。
- [MediaSessionService](https://developer.android.com/reference/androidx/media3/session/MediaSessionService)：后台播放架构。
- [FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Android 15 行为变化](https://developer.android.com/about/versions/15/behavior-changes-15)：服务类型和可见悬浮窗要求。
- [音频焦点](https://developer.android.com/media/optimize/audio-focus)、[播放捕获](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration)、[输入共享](https://developer.android.com/media/platform/sharing-audio-input)：跨应用播放与录音边界。
