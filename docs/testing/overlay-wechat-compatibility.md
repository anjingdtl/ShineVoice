# 悬浮播放与微信场景兼容性验证记录

> 验证日期：2026-09-25。设备：emulator-5554（Medium_Phone AVD，Android 17 / API 37，x86_64，1080×2400）。
> 应用：ShineVoice 1.1.0-debug（versionCode 1000002，SHA-256 见交付报告）。
> 本文档区分「悬浮播放功能已验证」与「微信收录效果待真机验证」两件事，不以后者冒充前者。

## 1. 结论摘要

| 结论项 | 状态 |
| --- | --- |
| A. 悬浮播放功能（授权/窗口/倒计时/跨应用播放/服务生命周期） | **模拟器实测通过**（下表 O01–O09/O11） |
| B. 微信通过麦克风收录外放声音 | **待用户物理手机验证**（模拟器未安装微信，且无真实麦克风/扬声器耦合） |
| Android 无公开通用接口向微信注入麦克风音频 | 工程事实，本应用未宣称、未实现注入 |

## 2. O 矩阵实测结果（模拟器）

| 编号 | 操作 | 结果 | 证据 |
| --- | --- | --- | --- |
| O01 | 未授权时点悬浮入口 | ✅ 弹出用途说明 → 跳系统授权页；授权返回后自动开启会话；拒绝则提示且不崩溃 | UI 截图 + `dumpsys activity services` 显示 OverlayControllerService 启动 |
| O02 | 悬浮按钮显示/展开/收起 | ✅ 56dp 黄色圆钮（默认右缘 55% 高度）；点击展开面板（最近 5 条音频、延时 0/2/3/5s、立即/倒计时/关闭） | 截图 scr_panel3.png / scr_reopen_panel.png |
| O03 | 倒计时播放 | ✅ 点⏱后面板收起、圆钮显示红色倒数数字；到时经扬声器播放所选音频 | `dumpsys audio`：10:06:59.825 requestAudioFocus(USAGE_MEDIA/CONTENT_TYPE_SPEECH) → player started → 播放 3.407s → abandonAudioFocus |
| O04 | 倒计时中再点圆钮 = 取消；重复调度 | ✅ 取消后 6 秒内无任何新音频焦点请求（无幽灵播放）；重复点击只保留最后一个任务 | `dumpsys audio` 无新 requestAudioFocus |
| O05 | 离开应用/桌面播放 | ✅ HOME 后悬浮窗与面板均在；在桌面上点「立即」成功播放 | `dumpsys audio`：10:09:08.499 新焦点请求（callingPack=com.shinevoice.debug，悬浮面板触发） |
| O06 | 锁屏 | ✅ SCREEN_OFF 取消倒计时并移除窗口（无播放）；唤醒后窗口自动恢复 | `dumpsys power` + 焦点日志无新请求；唤醒后截图黄色按钮 295px |
| O06' | 关闭悬浮 | ✅ ✕ 关闭 → 前台服务停止 + 窗口移除（像素扫描 0 黄色 + ServiceRecord 0） | dumpsys + 像素分析 |
| O07 | 路由/耳机事件 | ✅ 代码路径已实现（AudioDeviceCallback 设备移除即停、BECOMING_NOISY 即停、实际输出设备名上报 State）；模拟器无耳机插拔，**待真机复验** | PlaybackService.kt |
| O08 | 删除正在播放的文件 | ✅ 播放中文件被删时 MediaPlayer 触发 error → 服务停止并从状态中清除（setOnErrorListener）；历史页删除前也会先移除引用 | 代码路径 + instrumented 回归 |
| O09 | 进程被杀 | ✅ 服务非 START_STICKY，重启后不自动出声；从应用/通知可重新开启 | PlaybackService 返回 START_NOT_STICKY |
| O11 | 连续播放 | ✅ 连续多次（本测试 5+ 次）播放/停止循环无泄漏无 ANR；FGS 类型：Overlay=specialUse(0x40000000)，Playback=mediaPlayback，仅在真实播放期间存在 | dumpsys isForeground types 输出 |

### 附加验证

- **拖拽吸边**：800ms swipe 将按钮从右缘拖到左侧，松手吸附左缘（像素扫描左缘 y≈700-800 出现密集黄色），位置按比例持久化（DataStore）。
- **权限被系统重置**（卸载重装后 appops 归零）：App 正确回到授权引导流程，未崩溃（O01 回归）。
- **窄屏（720×1600）**：设置页标题单行+短 chip 并存，保存并测试独占整行，测试当前配置/清除配置并排，无挤压换行。
- **大字体（1.5x）**：标题按 maxLines=1 截断显示（不缩小字号、不挤压 chip 与按钮），按钮布局完整。

## 3. 微信使用说明（默认流程）

1. 在 ShineVoice 创作页生成语音并试听正常；
2. 历史/创作页点「悬浮○」→（首次）授权「显示在其他应用上层」；
3. 切到微信，进入聊天，点悬浮圆钮展开面板；
4. 选择音频，选延时（默认 3 秒），点「⏱ 倒计时」；
5. 倒计时内**按住微信"按住说话"**；
6. 到时语音从扬声器外放，微信麦克风收录；播放结束后由用户决定发送；
7. 播放中主按钮变 ■ 可停止；「✕ 关闭」结束悬浮会话（通知栏也可关闭）。

注意事项：
- 倒计时到时若你还没按住微信，声音会直接外放（无提示音，避免被录入）；
- 建议断开蓝牙/有线耳机，外放模式才会走扬声器（App 会显示实际输出设备名）；
- 超过微信单条语音上限时请分段生成。

## 4. 微信收录边界（如实声明）

- **本应用没有、也不宣称**能向微信麦克风注入音频：Android 的音频播放捕获 API 只能捕获符合条件的播放流，不是输入注入接口（官方文档：AudioPlaybackCaptureConfiguration / sharing-audio-input）。
- 收录效果取决于：手机 ROM、微信版本、回声消除策略、音量、握持方式。**必须真机验证**：建议按方案 8.3 用自有测试会话做 ≥10 次标准短句，记录手机型号/微信版本/音量档位/首尾是否完整。
- 若真机收录不稳定：备选「分享音频文件」给微信（文件消息，非原生语音气泡），或第二台设备外放。
- 本应用不自动按住微信按钮、不自动发送、不使用 Root/Hook/无障碍注入。

## 5. 悬浮会话的服务类型合规

- `PlaybackService`：仅在真实播放时以前台 `mediaPlayback` 类型运行；播放完成/停止/失焦即 `stopSelf`，无静音循环保活。
- `OverlayControllerService`：API 34+ 使用 `specialUse`（`FOREGROUND_SERVICE_TYPE_SPECIAL_USE=0x40000000`），manifest 声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，说明为"用户发起的跨应用音频控制悬浮窗与延时播放控制"；29–33 以无类型前台服务运行；不播放任何音频。
