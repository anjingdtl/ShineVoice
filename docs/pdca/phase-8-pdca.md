# Phase 8 PDCA（V0.1 收束：稳定性补测 + 三 Provider 严格切换 + 赛博朋克 UI 重构）

## Plan

四件事：① ZipVoice 50 次生成内存趋势补测（预热+稳定期+检查点，区分 Lazy Allocation
与线性泄漏）；② 三 Provider 严格切换（本地→系统→云端 ×7 = 21 次真实生成，云端失败
不再降级 non-fatal）；③ 全局赛博朋克 UI 重构（Phase A Token → B 组件 → C 创作 →
D 音色 → E 历史 → F 设置 → G 统一性，每轮 Build/Install/截图/Commit）；④ 最终构建
综合回归 + 生命周期验收 + 文档。

风险：UI 全量重写可能破坏既有功能（三 Provider、音色、历史、主题、路由持久化）；
MiniMax 真实调用消耗额度；测试进程无云端绑定导致 test04 前置失败。

## Do

- `E2eRealChainTest`：新增 `test07_zipVoiceFiftyRunMemoryTrend`（3 次预热 → GC+静置 →
  基线 → 50 次生成，run 5/10/20/30/40/50 记录 PSS/Java/Native Heap，分块耗时趋势）；
  重写 `test04_providerStrictThreeWayCycle`（严格三方式循环 + providerId/voiceId
  回显断言 + WAV 校验 + 限流退避重试 + 自给自足云端克隆兜底并落库绑定）。
- UI：新增 `ui/cyber/CyberTheme.kt`（CyberColors/Shape/Type/Spacing、双主题
  colorScheme 映射、mm:ss 格式化）与 `ui/cyber/CyberComponents.kt`（CyberBackground
  网格、CyberCard 斜切角+角标+荧光高亮、CyberSectionHeader 01 // CODE、CyberStatusChip、
  PulsingDot、SweepScanLine、CyberButton/OutlinedButton/TextField/FilterChip/Slider/
  NavigationBar/Dialog/CyberKV）；重写四页（CreateScreen/VoicesScreen/HistoryScreen/
  SettingsScreen 全部 Cyber 组件化，ShineVoiceRoot 精简为装配层）；edge-to-edge；
  状态栏图标对比度跟随应用内主题；styles.xml 深色基底防冷启动白闪。
- 去工程化收尾：结果卡与成功消息不再显示毫秒耗时；普通页面语义树零工程词。
- 顺手修复：音色页「重命名」按钮此前把旧名字原样传回（无效操作）→ 改为真正的
  重命名对话框；删除音色补确认对话框。

## Check

- 单测 29/29；`assembleDebug` 每轮通过并安装 emulator-5554。
- 50 次稳定性：50/50，PSS 在 run30 后平台化（最后 20 次 +329 KB），Native Heap
  平台化（+37 KB），Java Heap 恒定，avg 1,145 ms / avg RTF 0.194，无泄漏。
- 三 Provider 严格循环：21/21，回显全部正确，0 串线，历史 21 条正确落库。
- MiniMax 真实链路（最终构建）：连接/t2a（speech-2.8-hd，171 KB WAV）/错误 Key
  中文报错全部通过；7 个 commit diff 无密钥；`git status` 干净。
- UI：8 张全页面亮/暗截图（暗 21.0~34.8 / 亮 229.8~236.1 亮度量化 + 霓虹采样）；
  各页语义树取证；真实生成冒烟（新 UI 本地生成 00:05 + 播放 AudioFlinger 活动）；
  听筒路由 dumpsys `USAGE_VOICE_COMMUNICATION` 复证。
- 生命周期：生成中切后台回前台结果正确；强杀冷启恢复暗色主题/当前音色/听筒路由；
  本轮 Crash/ANR = 0。

## Act

- 过程问题与修复：① test04 首跑失败（无云端绑定）→ 测试改为自克隆+落库绑定后通过；
  ② 首次「暗色」截图组实际为亮色（持久化主题为 LIGHT）→ 切回暗色重拍 8 张齐套；
  ③ CyberDialog 参数顺序导致 trailing lambda 绑错 → content 移至末位。
- Commits：`b8f01f1`（E2E）、`cdde53a`（A+B）、`b192cf7`（C）、`e4711e0`（D）、
  `75e7e1a`（E）、`3d56a9d`（F）、`7810b88`（G）。
- 遗留：arm64 真机验收（听筒物理出声、Native 性能/内存）；云端音色删除接口路由、
  `get_voice` 不返回未使用克隆音色（官方限制）延续 Phase 7 待办。
