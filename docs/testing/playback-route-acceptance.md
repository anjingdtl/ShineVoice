# 外放/听筒播放切换 — 验收证据（2026-09-01）

## 背景与范围

补齐最初产品愿景的最后一块：播放输出支持「外放 / 听筒」切换。改动：

| 文件 | 内容 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | 新增 `MODIFY_AUDIO_SETTINGS` 权限 |
| `core/audio/PlaybackRoute.kt`（新） | SPEAKER/EARPIECE 枚举，含持久化解析 |
| `core/audio/AudioRouteManager.kt`（新） | 听筒路由：API 31+ `setCommunicationDevice(TYPE_BUILTIN_EARPIECE)`，API 24–30 `MODE_IN_COMMUNICATION + setSpeakerphoneOn(false)`；结束恢复 `MODE_NORMAL` |
| `core/storage/AudioPlaybackController.kt` | 播前显式设置 `AudioAttributes`（听筒=USAGE_VOICE_COMMUNICATION，外放=USAGE_MEDIA，均 CONTENT_TYPE_SPEECH）；`updateRoute()` 支持播放中重建续播；停止/播完恢复路由 |
| `data/settings/SettingsStore.kt` | `playback_route` DataStore 键（默认外放） |
| `ui/MainViewModel.kt` | `playbackRoute` 状态 + collect + `onPlaybackRouteChanged` |
| `ui/ShineVoiceRoot.kt` | 设置页「音频」区块新增「播放输出」选择；创作页结果卡新增快捷切换按钮；路由变化经 `LaunchedEffect` 即时下发 controller |
| `app/src/test/.../PlaybackRouteTest.kt`（新） | 枚举解析单测 |

## 验证结果（模拟器 emulator-5554，debug 构建）

### 1. 单元测试
`:app:testDebugUnitTest` 全部通过：29/29（含新增 PlaybackRouteTest 2 项），零回归。

### 2. 听筒模式路由生效（dumpsys audio 取证）
设置页选「听筒」→ 历史页播放：
```
AudioPlaybackConfiguration piid:111 state:started
  attr: usage=USAGE_VOICE_COMMUNICATION content=CONTENT_TYPE_SPEECH sampleRate=24000
Requested mode = MODE_IN_COMMUNICATION
  setMode(MODE_IN_COMMUNICATION) from package=com.shinevoice.debug
```
播放自然结束后自动恢复 `Requested mode = MODE_NORMAL`（deactivate 生效，不残留通话模式）。

### 3. 播放中即时切换（创作页结果卡快捷按钮）
事件时间线：
```
14:09:11.936 new player piid:119（听筒模式开始播放）
14:09:12.035 setMode(MODE_IN_COMMUNICATION)
14:09:12.882 new player piid:127（点击切换 → 按新路由重建并从当前位置续播）
14:09:12.989 setMode(MODE_NORMAL)（外放激活，撤销听筒借用）
```
UI 按钮文本同步变为「播放输出：外放 · 点击切换听筒/外放」。

### 4. 设置持久化（冷重启）
选「听筒」→ `am force-stop` → 冷启动 → 历史页播放：
```
attr: usage=USAGE_VOICE_COMMUNICATION content=CONTENT_TYPE_SPEECH state:started
Requested mode = MODE_IN_COMMUNICATION
```
重启后听筒设置保留并生效。

### 5. 截图存档
- `artifacts/qa/playback-route/01-settings-earpiece-selected.png`
- `artifacts/qa/playback-route/02-settings-playback-output.png`

## 已知边界

- 本模拟器（sdk_gphone16k_x86_64）无内置听筒硬件（`getAvailableCommunicationDevices: no EARPIECE!`），`setCommunicationDevice` 找不到设备时代码按设计优雅降级（不 crash，回退扬声器）。真机验收口径：`USAGE_VOICE_COMMUNICATION` 属性 + MODE 事件 + UI 切换均已在模拟器证实，听筒出声需 arm64 真机复测。
- 听筒模式走通话音量流，播放时按音量键调节（设置页有提示文案）。
- 外放模式插耳机自动走耳机（系统默认媒体路由）。
