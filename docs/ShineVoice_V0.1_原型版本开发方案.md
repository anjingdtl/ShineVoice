# ShineVoice V0.1 原型版本开发方案

> **项目名称：** ShineVoice  
> **目标版本：** V0.1 Prototype  
> **平台：** Android  
> **核心定位：** 本地优先、云端增强、Provider 可扩展的中文 AI 声音克隆与 TTS 工作台  
> **本地引擎：** sherpa-onnx + ZipVoice-Distill INT8  
> **系统引擎：** Android System TTS  
> **在线引擎：** MiniMax API  
> **开发原则：** 先 Kernel、后 UI；每阶段独立验证、PDCA、Commit

---

# 1. 项目定位

ShineVoice 不应只是 ZipVoice 的 Android 外壳，而应从 V0.1 开始建立统一的语音引擎架构。

目标是在同一个 Android APP 中支持：

```text
本地声音克隆
    └── sherpa-onnx + ZipVoice

Android 系统朗读
    └── Android System TTS

高质量在线生成
    └── MiniMax API

未来更多模型 / API
    └── 新增 Provider
```

上层的创作、音色库、历史记录、播放器、输出文件和设置，不应与具体模型或厂商绑定。

ShineVoice 的长期定位：

> **本地优先、云端增强的 Android AI 语音工作台。**

---

# 2. V0.1 核心目标

V0.1 的成功标准不是功能数量，而是证明三条 Provider 链路可以稳定共存。

## 2.1 本地 ZipVoice

必须跑通：

```text
录音 / 导入参考音频
        ↓
音频标准化
        ↓
reference.wav + referenceText
        ↓
sherpa-onnx + ZipVoice-Distill INT8
        ↓
输入中文 targetText
        ↓
Android 本地生成
        ↓
播放 / 保存
```

要求：

- 无网络可用；
- 中文可用；
- Android 本地真实推理；
- CPU 环境即可运行；
- 不使用 Mock 替代验收；
- 可连续稳定生成。

## 2.2 Android System TTS

必须实现：

- 检测系统 TTS Engine；
- 枚举可用 Voice；
- 选择中文语音；
- speechRate；
- pitch；
- 正常播放；
- 正确管理 `TextToSpeech` 生命周期；
- 在系统能力允许时支持文件输出。

## 2.3 MiniMax Cloud

必须实现：

- BYOK：用户自行填写 MiniMax API Key；
- API Key 安全存储；
- 测试连接；
- 上传参考音频；
- 创建克隆音色；
- 保存 `voice_id`；
- 云端 TTS；
- 音频下载；
- 播放；
- 保存；
- 分享；
- 网络/API 异常处理。

禁止把开发者公共 API Key 固化进 APK。

---

# 3. V0.1 功能范围

## 3.1 创作

支持：

- 输入文本；
- 选择 Provider；
- 选择音色；
- 调整 Provider 支持的参数；
- 生成；
- 播放；
- 保存；
- 分享；
- 重新生成；
- 显示生成耗时；
- 显示 Provider；
- 显示错误状态。

## 3.2 音色库

支持：

- 创建音色；
- 录音；
- 导入音频；
- 音频预处理；
- `referenceText` 编辑；
- 本地 ZipVoice 绑定；
- MiniMax `voice_id` 绑定；
- Android TTS Voice 绑定；
- 重命名；
- 删除；
- 试听。

## 3.3 历史记录

至少保存：

```text
输入文本
Provider
VoiceProfile
模型
生成时间
生成耗时
音频路径
成功 / 失败
错误类型
```

## 3.4 设置

至少包括：

### ZipVoice
- 模型状态；
- 模型目录；
- 文件完整性；
- 重新检测；
- Debug 状态。

### Android TTS
- Engine；
- Voice；
- language；
- speechRate；
- pitch。

### MiniMax
- API Key；
- 测试连接；
- Provider 状态；
- 默认模型；
- 清除配置。

### 音频
- 默认输出目录；
- 默认输出格式；
- 是否自动保存。

---

# 4. V0.1 明确不做

暂不进入原型：

- 用户注册/登录；
- 云同步；
- 会员与商业计费；
- 自建 API Gateway；
- 声音市场；
- 声音社区；
- 多角色剧本；
- 视频配音；
- 字幕；
- 多轨编辑；
- QNN/NPU/GPU 专项优化；
- Streaming；
- 情感时间轴；
- 系统级 Android TTS Engine Service；
- 自动适配任意第三方 REST API；
- 大量动画与视觉精装修。

原则：

> **稳定性 > 功能数量。**

---

# 5. 技术栈

建议：

```text
Kotlin
Jetpack Compose
Material 3
AndroidX
Coroutines / Flow
Room
DataStore
OkHttp
Kotlin Serialization
Media3
Android Keystore
```

AI Runtime：

```text
sherpa-onnx
+
ZipVoice-Distill INT8
```

---

# 6. 总体架构

```text
┌────────────────────────────────────┐
│             ShineVoice             │
├────────────────────────────────────┤
│         Presentation Layer         │
│ Compose / ViewModel / UI State     │
├────────────────────────────────────┤
│           Domain Layer             │
│ TtsManager / VoiceManager          │
│ UseCase / Repository               │
├────────────────────────────────────┤
│       Unified Provider API         │
├─────────────┬────────────┬─────────┤
│ Sherpa      │ Android    │ MiniMax │
│ ZipVoice    │ System TTS │ Cloud   │
│ Provider    │ Provider   │Provider │
├─────────────┴────────────┴─────────┤
│ Audio / Storage / Security / DB    │
├────────────────────────────────────┤
│ JNI / sherpa-onnx / ONNX Runtime   │
└────────────────────────────────────┘
```

---

# 7. Provider 抽象

Provider 是 ShineVoice 最重要的工程边界。

禁止在 UI / ViewModel 中散落：

```kotlin
if (provider == "minimax") { ... }
```

统一接口建议：

```kotlin
interface TtsProvider {
    val id: String
    val displayName: String

    suspend fun initialize(): ProviderResult
    suspend fun getCapabilities(): TtsCapabilities
    suspend fun getVoices(): List<TtsVoice>
    suspend fun synthesize(request: TtsRequest): TtsResult
    suspend fun cancel(taskId: String)
    suspend fun validateConfig(): ProviderResult
    suspend fun release()
}
```

声音克隆能力：

```kotlin
interface VoiceCloneProvider : TtsProvider {
    suspend fun cloneVoice(
        request: VoiceCloneRequest
    ): VoiceCloneResult

    suspend fun deleteRemoteVoice(
        voiceId: String
    ): ProviderResult
}
```

首批 Provider：

```text
SherpaZipVoiceProvider
AndroidSystemTtsProvider
MiniMaxProvider
```

---

# 8. Provider Registry 与 TtsManager

建立统一：

```text
ProviderRegistry
TtsManager
```

`ProviderRegistry` 负责：

- 注册 Provider；
- 获取 Provider；
- 查询状态；
- 查询 Capability；
- 禁止 UI 自行创建 Provider。

`TtsManager` 负责：

```text
TtsRequest
    ↓
定位 Provider
    ↓
能力检查
    ↓
任务排队
    ↓
调用 Provider
    ↓
标准化 TtsResult
    ↓
写入历史记录
    ↓
返回 UI
```

V0.1 默认：

> **单任务、保守并发。**

---

# 9. Unified Request / Result / Capability

建议：

```kotlin
data class TtsRequest(
    val taskId: String,
    val text: String,
    val providerId: String,
    val voiceProfileId: String?,
    val voiceId: String?,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val emotion: String? = null,
    val outputFormat: AudioFormat,
    val extra: Map<String, String> = emptyMap()
)
```

```kotlin
data class TtsResult(
    val taskId: String,
    val providerId: String,
    val success: Boolean,
    val audioFile: String?,
    val durationMs: Long?,
    val sampleRate: Int?,
    val model: String?,
    val voiceId: String?,
    val elapsedMs: Long,
    val error: TtsError?
)
```

```kotlin
data class TtsCapabilities(
    val supportsVoiceClone: Boolean,
    val supportsOffline: Boolean,
    val supportsStreaming: Boolean,
    val supportsSpeed: Boolean,
    val supportsPitch: Boolean,
    val supportsEmotion: Boolean,
    val supportsFileOutput: Boolean,
    val supportedFormats: Set<AudioFormat>
)
```

UI 根据 Capability 动态显示参数。

---

# 10. VoiceProfile

用户管理的是“音色”，不是 Provider 内部对象。

建议：

```text
VoiceProfile
│
├── id
├── displayName
├── avatar
├── createdAt
├── updatedAt
│
├── reference
│   ├── sourceAudio
│   ├── normalizedAudio
│   └── referenceText
│
├── bindings
│   ├── ZIPVOICE_LOCAL
│   ├── MINIMAX
│   └── ANDROID_TTS
│
└── metadata
```

同一声音允许：

```text
我的声音
├── ZipVoice 本地版本
└── MiniMax 云端版本
```

禁止产品层制造两个重复的“我的声音”。

---

# 11. SherpaZipVoiceProvider

采用：

```text
sherpa-onnx
+
ZipVoice-Distill INT8
```

V0.1 不做：

- NPU；
- QNN；
- GPU；
- Streaming；
- 多任务并发。

第一优先级：

```text
reference.wav
+
referenceText
+
targetText
    ↓
generated.wav
    ↓
Android 播放
```

这条链路未真实跑通以前：

> 不进入 UI 精装修。

---

# 12. Native Runtime 生命周期

JNI / Native Runtime 必须封装。

建议：

```text
SherpaRuntimeManager
        ↓
SherpaZipVoiceProvider
        ↓
TtsManager
```

禁止：

- Compose 页面直接调 JNI；
- ViewModel 直接管理 Runtime；
- 每次点击都无意义重建全部 Native 对象；
- 页面销毁后残留 Native Task；
- Native Exception 直接导致 APP Crash。

底层错误必须转换成统一业务错误。

---

# 13. 模型管理

模型与业务代码隔离。

建议：

```text
models/
└── zipvoice/
    ├── encoder.onnx
    ├── decoder.onnx
    ├── vocoder.onnx
    └── ...
```

建立：

```text
ModelManifest
```

至少包含：

```text
modelId
version
engine
requiredFiles
checksum
size
installedAt
```

V0.1 至少做到：

- 检查模型目录；
- 检查必需文件；
- 模型缺失提示；
- 模型加载状态；
- 重新检测；
- 模型异常不得 Crash。

大型模型默认不提交 Git。

必须完善：

- `.gitignore`；
- 模型获取说明；
- 模型放置目录；
- 模型版本记录。

---

# 14. 音频预处理

统一 `AudioPipeline`：

```text
录音 / 导入
     ↓
解码
     ↓
Mono
     ↓
重采样
     ↓
PCM
     ↓
裁剪首尾静音
     ↓
reference.wav
```

要求：

- WAV 可正常读取；
- 常见 Android 音频可导入；
- 非法文件有保护；
- 转换失败有中文提示；
- 损坏文件不得直接进入 Native 推理。

---

# 15. referenceText 与 ASR

V0.1：

- 用户可填写；
- 用户可编辑；
- 非空检查；
- 与 VoiceProfile 持久化。

预留：

```text
SpeechRecognitionProvider
```

但 ASR 自动识别不得阻塞 Phase 1。

优先级：

```text
ZipVoice 真机稳定
>
本地音色管理
>
MiniMax
>
ASR 自动识别
```

---

# 16. MiniMax Provider

职责：

```text
API Key
↓
连接检查
↓
上传参考音频
↓
Voice Clone
↓
voice_id
↓
TTS
↓
音频
↓
播放 / 保存
```

V0.1 使用：

> BYOK。

安全要求：

- API Key 不进入源码；
- 不进入 BuildConfig；
- 不明文 SharedPreferences；
- 不输出 Logcat；
- 使用 Android Keystore + 加密配置；
- 网络日志隐藏 Authorization / Token。

---

# 17. Android System TTS Provider

职责：

- `TextToSpeech` 初始化；
- Engine 枚举；
- Voice 枚举；
- language；
- speed；
- pitch；
- speak；
- 文件输出能力探测；
- shutdown。

必须独立于 ZipVoice，不共享不必要的实现细节。

---

# 18. 页面结构

V0.1 四个一级页面：

```text
创作
音色
历史
设置
```

## 创作

```text
ShineVoice

[引擎：本地 ZipVoice ▼]
[音色：我的声音 ▼]

┌─────────────────────┐
│ 输入需要生成的中文文本 │
└─────────────────────┘

[生成语音]

▶ 00:00 ━━━━━━━ 00:08

Provider：ZipVoice
耗时：xxxx ms

[保存] [分享] [重新生成]
```

## 音色

```text
我的音色

[+ 创建音色]

我的声音
本地 ZipVoice / MiniMax
▶ 试听
```

## 历史

展示：

- 时间；
- 文本摘要；
- VoiceProfile；
- Provider；
- 耗时；
- 状态；
- 播放；
- 保存；
- 分享。

## 设置

展示：

- ZipVoice 模型；
- Android TTS；
- MiniMax；
- 音频输出；
- 高级 Debug。

---

# 19. 创建音色流程

```text
Step 1
录音 / 导入

Step 2
音频预处理

Step 3
输入或确认 referenceText

Step 4
填写音色名称

Step 5
✓ 创建本地 ZipVoice 音色
□ 同时创建 MiniMax 云端音色
```

MiniMax 未配置：

> 不能阻塞本地音色创建。

---

# 20. 数据与文件

Room 建议实体：

```text
VoiceProfileEntity
VoiceBindingEntity
GenerationHistoryEntity
ProviderConfigEntity
ModelPackageEntity
```

API Key 不进入 Room 明文。

文件结构建议：

```text
app data/
├── models/
│   └── zipvoice/
├── voices/
│   └── {voiceProfileId}/
│       ├── source.*
│       ├── reference.wav
│       └── metadata.json
├── generated/
├── cache/
└── logs/
```

要求数据库与文件系统保持一致。

---

# 21. 错误体系

统一：

```text
TtsError
├── ProviderNotInitialized
├── ModelNotInstalled
├── ModelCorrupted
├── InvalidReferenceAudio
├── InvalidReferenceText
├── UnsupportedFormat
├── NetworkUnavailable
├── ApiUnauthorized
├── ApiRateLimited
├── ApiServerError
├── GenerationTimeout
├── NativeRuntimeError
├── StorageError
└── Unknown
```

UI 显示中文业务错误。

Debug 日志保留底层原因。

---

# 22. 生命周期与并发

V0.1：

- ZipVoice：单任务串行；
- MiniMax：UI 先单任务；
- Android TTS：统一任务管理。

重点验证：

- 页面退出；
- 前后台；
- Activity 重建；
- ViewModel 销毁；
- Runtime 释放；
- Player 释放；
- Android TTS shutdown；
- 网络任务取消。

---

# 23. 隐私与使用约束

V0.1 即建立：

- 首次使用声音克隆时提示授权要求；
- 仅使用本人或已获授权的声音；
- 本地模式默认不上传；
- MiniMax 上传前明确提示；
- 日志不记录 API Key；
- 支持删除本地 VoiceProfile；
- 为远程删除预留 Provider API。

提示文案建议：

> 请仅使用本人声音或已获得明确授权的声音素材。

---

# 24. 工程目录

```text
app/
├── core/
│   ├── audio/
│   ├── common/
│   ├── model/
│   ├── security/
│   └── storage/
├── domain/
│   ├── tts/
│   ├── voice/
│   └── history/
├── provider/
│   ├── sherpa/
│   ├── androidtts/
│   └── minimax/
├── data/
│   ├── db/
│   ├── repository/
│   └── settings/
├── feature/
│   ├── create/
│   ├── voices/
│   ├── history/
│   └── settings/
└── debug/
```

V0.1 不要过早拆大量 Gradle Module。

---

# 25. 项目命名

正式名称统一为：

```text
ShineVoice
```

要求：

- APP Name：ShineVoice；
- README：ShineVoice；
- 文档：ShineVoice；
- 新代码不得继续出现 VoiceCloneCN；
- Package 不得使用 `com.example`。

---

# 26. 开发阶段

```text
Phase 0  工程骨架
Phase 1  ZipVoice Android 本地闭环
Phase 2  本地音色管理
Phase 3  Android System TTS
Phase 4  MiniMax Provider
Phase 5  Unified VoiceProfile
Phase 6  完整原型 UI
Phase 7  综合穿测与收束
```

每个 Phase：

> 开发 → Check → PDCA → 独立 Commit。

---

# 27. Phase 0：工程骨架

任务：

- Android 项目；
- Kotlin；
- Compose；
- Material 3；
- 四页导航；
- Provider API；
- Provider Registry；
- TtsManager 骨架；
- Room；
- DataStore；
- 日志。

验收：

```text
Gradle Sync
assembleDebug
APK 生成
安装
启动
四页面可访问
Provider Registry 可工作
```

Commit：

```text
feat: initialize ShineVoice Android prototype architecture
```

---

# 28. Phase 1：ZipVoice 本地闭环

这是当前最高优先级。

必须实际接入：

```text
sherpa-onnx
+
ZipVoice-Distill INT8
```

使用固定：

```text
reference.wav
referenceText
```

输入：

```text
targetText
```

输出：

```text
generated.wav
```

并能够在 Android 播放。

## Phase 1 验收

必须：

1. Debug APK 构建；
2. 安装 Android；
3. 启动 ShineVoice；
4. 加载模型；
5. 输入中文；
6. 本地生成；
7. 输出 WAV；
8. 播放；
9. 无网络仍可生成；
10. APP 重启后再次成功生成。

## 稳定性

至少连续生成：

```text
20 次
```

记录：

- 成功率；
- 平均耗时；
- 最大耗时；
- RTF；
- Crash；
- ANR；
- Native 错误；
- 内存变化。

禁止仅凭 Build 成功宣布 Phase 1 完成。

Commit：

```text
feat: integrate sherpa-onnx ZipVoice local voice cloning
```

---

# 29. Phase 2：本地音色

完成：

- 录音；
- 导入；
- AudioPipeline；
- reference.wav；
- referenceText；
- VoiceProfile；
- 多音色；
- 删除；
- 重命名；
- 试听。

验收至少：

```text
1 个男声
1 个女声
1 个导入音频音色
```

均可本地生成。

---

# 30. Phase 3：Android System TTS

完成：

```text
AndroidSystemTtsProvider
```

验收：

创作页能够从 ZipVoice 切换到 Android System TTS，而无需重写业务流程。

---

# 31. Phase 4：MiniMax Provider

完成：

- API Key；
- Keystore；
- 连接测试；
- 音频上传；
- Voice Clone；
- `voice_id`；
- TTS；
- 下载；
- 播放；
- 保存；
- timeout；
- 网络/API 错误。

验收：

```text
配置 Key
→ 上传
→ 克隆
→ 保存 voice_id
→ 输入中文
→ TTS
→ 播放
→ 保存
```

APP 重启后仍可使用已有远程音色。

---

# 32. Phase 5：Unified VoiceProfile

实现：

```text
我的声音
├── ZipVoice
└── MiniMax
```

同一 VoiceProfile 可以切换 Provider。

---

# 33. Phase 6：完整原型 UI

普通用户无需 Debug 页面即可完成：

```text
创建音色
→ 输入文字
→ 选择 Provider
→ 生成
→ 试听
→ 保存
```

---

# 34. Phase 7：综合穿测

## ZipVoice

至少测试：

- 5/10/50/100/200 字；
- 中文标点；
- 数字；
- 英文；
- 中英文混合；
- 男声；
- 女声；
- 低音量；
- 轻噪音；
- 空 referenceText；
- 空 targetText；
- reference.wav 缺失；
- 模型缺失；
- 模型损坏；
- 存储不足。

## MiniMax

至少测试：

- 正确/错误 Key；
- 无网络；
- 网络切换；
- timeout；
- 上传失败；
- clone 失败；
- TTS 失败；
- 无效 voice_id；
- 长文本；
- APP 重启；
- 连续生成。

## Android TTS

至少测试：

- 多 Engine；
- 中文 Voice；
- speed；
- pitch；
- Activity 重建；
- shutdown；
- 连续朗读。

## Provider 切换

连续切换：

```text
ZipVoice ↔ Android TTS ↔ MiniMax
```

至少：

```text
20 次
```

---

# 35. 模拟器与真机测试

如果本机存在 Android Emulator：

必须执行：

```text
assembleDebug
→ 安装 / 升级 APK
→ 启动 ShineVoice
→ UI 操作
→ 可行的 Provider 测试
→ Logcat 检查
```

如果模拟器由于 ABI / 性能 / Native Runtime 无法完成 ZipVoice 推理：

- 明确区分工程问题和模拟器限制；
- 继续完成模拟器 UI/生命周期测试；
- 如有真机，必须继续真机推理验证。

原则：

> **AI + JNI + Native Runtime 最终以真实设备运行结果作为有效验收依据。**

---

# 36. 性能与 Debug

每次生成 Debug 建议记录：

```text
provider
model
textLength
elapsedMs
audioDurationMs
RTF
success
error
memory
```

隐藏 Debug 页面建议显示：

- APP Version；
- Android Version；
- Device；
- ABI；
- Provider 状态；
- sherpa-onnx 状态；
- 模型目录；
- 文件检查；
- Native Init 时间；
- 最近生成耗时；
- RTF；
- 内存；
- MiniMax 状态；
- 日志导出。

---

# 37. PDCA

每个 Phase 完成后执行：

## Plan
确认本阶段目标、边界和验收项。

## Do
实施开发。

## Check
检查：

- 构建；
- 测试；
- APK；
- 模拟器；
- 真机；
- Logcat；
- Crash；
- Native；
- 内存；
- 生命周期。

## Act
只处理：

- 已确认 Bug；
- 当前 Phase 阻塞；
- 明确架构缺陷。

禁止借 PDCA 无边界扩展功能。

---

# 38. Git 要求

每个 Phase 独立 Commit。

禁止：

- 多 Phase 混成巨型 Commit；
- 未测试就标记完成；
- 模型大文件进入 Git；
- API Key 进入 Git；
- build 产物进入 Git；
- Debug 临时文件进入 Git。

---

# 39. Agent 自主执行要求

Agent 执行本方案时：

- 先读取本地工程；
- 读取本方案；
- 检查 Android SDK / Gradle / 模拟器；
- 检查 sherpa-onnx 当前接口；
- 检查 ZipVoice 模型；
- 自主编码；
- 自主构建；
- 自主安装；
- 自主测试；
- 自主定位并修复普通工程问题。

Gradle、JNI、CMake、ABI、模型路径、依赖、Manifest、权限、Audio、Room、Compose 等普通问题，不应直接中止等待人工介入。

## 修复边界

禁止：

- 为解决局部问题重写 sherpa-onnx；
- 无依据大规模修改第三方库；
- 无边界升级依赖；
- 未完成 Phase 1 就提前大规模开发云端功能；
- 用 Mock 冒充真实声音克隆；
- 为追求“漂亮架构”破坏已工作的 Native 链路。

原则：

> **最小修改、验证优先、稳定优先。**

---

# 40. 当前立即执行范围

当前第一轮建设只聚焦：

```text
Phase 0
+
Phase 1
```

固定执行顺序：

```text
1. 检查 ShineVoice 根目录
2. 阅读本方案
3. 建立 Android 工程
4. 建立 Provider API
5. 建立 ProviderRegistry
6. 建立 TtsManager
7. 完成四页基础 UI
8. Build
9. 安装模拟器
10. Phase 0 PDCA
11. Commit
12. 接入 sherpa-onnx
13. 准备 ZipVoice-Distill INT8
14. 固定 reference.wav
15. 固定 referenceText
16. 输入中文 targetText
17. Android 真实推理
18. 生成 WAV
19. 播放
20. 保存
21. 断网测试
22. 连续 20 次生成
23. 生命周期测试
24. Logcat 检查
25. 内存检查
26. Phase 1 PDCA
27. Commit
```

Phase 1 未验收前，不进入大规模 UI 或 MiniMax 开发。

---

# 41. V0.1 最终验收

## A. 本地 ZipVoice

```text
录音 / 导入
→ VoiceProfile
→ ZipVoice
→ 中文文本
→ 本地生成
→ 播放
→ 保存
```

离线成立。

## B. Android System TTS

```text
选择 Engine
→ Voice
→ 中文
→ 朗读
```

成立。

## C. MiniMax

```text
配置 Key
→ 上传参考音频
→ Voice Clone
→ voice_id
→ 中文文本
→ TTS
→ 播放
→ 保存
```

成立。

## D. Unified VoiceProfile

同一“我的声音”可以绑定：

```text
ZipVoice
MiniMax
```

## E. Provider Switch

创作页面直接切换：

```text
ZipVoice
Android System TTS
MiniMax
```

无需重写业务层。

## F. 稳定性

至少：

```text
ZipVoice 连续生成 20 次
MiniMax 连续生成 10 次
Android TTS 连续播放 20 次
Provider 切换 20 次
```

并完成：

- 前后台；
- APP 重启；
- 模型异常；
- 网络异常；
- 文件异常。

不得存在稳定复现 Crash。

---

# 42. 原型交付物

最终至少包含：

```text
Android 源码
Debug APK
README.md
ARCHITECTURE.md
Provider 接口说明
模型部署说明
MiniMax 配置说明
测试记录
性能记录
PDCA 记录
已知问题列表
```

建议目录：

```text
docs/
├── architecture/
├── prototype/
├── testing/
├── pdca/
└── decisions/
```

---

# 43. 后续演进

## V0.2
- sherpa-onnx 中文 ASR；
- 自动 referenceText；
- VAD；
- 录音质量检测；
- 模型下载器；
- 长文本分段；
- 智能重试。

## V0.3
- Smart Router；
- 本地优先；
- 云端高质量；
- Provider Fallback；
- 更多在线 Provider。

## V1.0
- 正式中文 UI；
- 完整模型管理；
- 多 Provider；
- 完整历史；
- 完整导出；
- 隐私与授权流程；
- Release 构建；
- 真机矩阵。

---

# 44. ShineVoice 总原则

1. **本地优先**：无网仍有核心声音克隆能力。  
2. **云端增强**：MiniMax 等 Provider 提供更高质量能力。  
3. **Provider 解耦**：具体模型不得污染业务层。  
4. **VoiceProfile 统一**：用户管理“音色”，而不是厂商对象。  
5. **先 Kernel 后 UI**：本地推理稳定以前不精装修。  
6. **保守并发**：V0.1 优先稳定。  
7. **模型可替换**：ZipVoice 不是不可替换依赖。  
8. **API Key 不进入 APK**：BYOK + Keystore。  
9. **每阶段 PDCA**：验证后再扩展。  
10. **每阶段独立 Commit**：保持可回退。  
11. **模拟器必须测**：安装、UI、生命周期和可行推理。  
12. **真机结果优先**：Native AI 项目最终看真实运行。  
13. **禁止 Mock 冒充完成**：核心链路必须真实生成。  
14. **先收束再扩展**：原型阶段不无限加功能。

---

# 45. 当前阶段结论

当前 ShineVoice 第一轮建设目标明确冻结为：

```text
ShineVoice Android 工程
        ↓
Unified TTS Kernel
        ↓
sherpa-onnx
        ↓
ZipVoice-Distill INT8
        ↓
reference.wav
+
referenceText
+
中文 targetText
        ↓
Android 本地真实生成
        ↓
播放
        ↓
保存
        ↓
20 次连续稳定性测试
```

只有完成并验收以上 **Phase 0 + Phase 1**，才进入本地音色管理、Android System TTS、MiniMax 和后续完整产品化阶段。

ShineVoice V0.1 的真正目标，是先把 **统一 Provider 架构、Native Runtime、VoiceProfile、音频体系和真实 Android 稳定性** 打牢，使后续替换模型或新增在线 API 时无需推翻上层产品架构。
