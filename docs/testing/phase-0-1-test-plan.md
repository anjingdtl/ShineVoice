# Phase 0/1 测试计划

## Phase 0

1. `:app:testDebugUnitTest`
2. `:app:assembleDebug`
3. `:app:installDebug`
4. 启动并确认四个底部页面可访问。
5. 确认 `ProviderRegistry` 注册/初始化快照和 `TtsManager` 单任务路径。
6. 检查 Logcat 无启动崩溃。

## Phase 1

1. 准备官方模型、vocoder 与 `reference.wav`，输入官方匹配 `referenceText`。
2. 关闭 Wi-Fi/移动数据，完成至少一次真实本地中文生成。
3. 播放生成 WAV，通过 SAF 保存。
4. 强制停止并重新启动，二次生成。
5. Home/恢复前后台，检查播放资源释放和页面恢复。
6. 设置页运行连续 20 次真实 Native 生成，记录成功率、平均/最大耗时、音频时长和 RTF。
7. 采集 `dumpsys meminfo` 前后、Logcat 普通错误/Crash buffer、ANR 证据。

任何 Native Crash、ANR 或稳定复现的失败都会阻止 Phase 1 标记完成。

