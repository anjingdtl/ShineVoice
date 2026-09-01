# Phase 3 PDCA（生成历史）

## Plan

把“历史”改造成用户已生成的声音文件库：按日期分组、日期折叠（默认今天展开）、点击历史直接播放、迷你播放器、多选、全选、批量删除、批量分享、ZIP 打包导出。普通 UI 不展示工程 Debug 记录（providerId/耗时/RTF/错误码），只展示文本、时间、时长与成败。

影响面：`GenerationHistoryDao` 全量查询与删除接口、`AudioPlaybackController` 补完成回调、`AudioExporter`（ZIP）、ViewModel 选择/播放状态、History 页重写。DB schema 不变。

## Do

- DAO/Repository：新增 `observeAll()`、`getByIds()`、`deleteByIds()`（ViewModel 删除行 + 物理 WAV 文件，保持文件与 DB 一致）。
- `AudioPlaybackController`：补 `onCompletion` 回调与 `currentFile`，迷你播放器/播放完成时可清理状态。
- `AudioExporter.exportAsZip`：把选中 WAV 打包为 ZIP（文件名清洗中文/数字），通过 SAF 保存。
- ViewModel：`historySelection`（多选）、`nowPlayingTaskId/title`、`setHistorySelectAll`、`deleteSelectedHistory`（DB+文件一致性）、`setNowPlaying`；历史流改收 `observeAll`。
- History UI：日期分组（今天/昨天/MM月dd日）、折叠（默认今天展开）、选择模式（多选/全选/完成）、批量操作栏（删除/分享/导出 ZIP）、点击即播、迷你播放器（停止按钮）、分享走 `ACTION_SEND_MULTIPLE` + FileProvider。
- `file_paths.xml` 增加 `exports/`；Application 提供 `zippedExport` 暂存。

## Check

- CI：`:app:testDebugUnitTest` + `:app:assembleDebug`（见对应 commit run）。
- 日期分组/折叠/迷你播放器/多选/批量删除与文件清理/分享/ZIP 导出：涉及 Intent 与文件交互，**待真机验收**。
- 播放完成自动收起迷你播放器：**待真机验收**。
- 不影响生成链路与音色库。

## Act

- CI 报错即修复重推。
- 待真机验收项：批量删除后 DB 与 generated 目录一致性；ZIP 导出内容可解压可播放；分享给系统应用正常；跨日分组展示。