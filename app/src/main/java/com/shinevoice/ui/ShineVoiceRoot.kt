package com.shinevoice.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinevoice.ShineVoiceApplication
import com.shinevoice.core.audio.PlaybackRoute
import com.shinevoice.core.storage.AudioPlaybackController
import com.shinevoice.data.settings.ThemeMode
import com.shinevoice.domain.tts.TtsResult
import com.shinevoice.ui.cyber.CyberBackground
import com.shinevoice.ui.cyber.CyberNavItem
import com.shinevoice.ui.cyber.CyberNavigationBar
import com.shinevoice.ui.cyber.CyberTheme
import java.io.File
import java.util.Locale

private enum class AppPage(val title: String) {
    CREATE("创作"),
    VOICES("音色"),
    HISTORY("历史"),
    SETTINGS("设置"),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ShineVoiceRoot(
    viewModel: MainViewModel,
    playbackController: AudioPlaybackController,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val application = context.applicationContext as ShineVoiceApplication
    var selectedPage by remember { mutableIntStateOf(AppPage.CREATE.ordinal) }
    var showVoicePicker by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { destination ->
        if (destination == null) return@rememberLauncherForActivityResult
        val source = state.lastResult?.audioFile?.let(::File)
        if (source == null) {
            Toast.makeText(context, "没有可保存的 WAV。", Toast.LENGTH_SHORT).show()
        } else {
            application.wavStorage.copyToUri(source, destination)
                .onSuccess { Toast.makeText(context, "WAV 已保存。", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    DisposableEffect(playbackController) {
        playbackController.onCompletion = { viewModel.setNowPlaying(null, null) }
        onDispose {
            playbackController.onCompletion = null
            playbackController.release()
        }
    }

    // 路由变化即时生效：空闲时供下次播放使用，播放中会按新路由续播。
    LaunchedEffect(state.playbackRoute) {
        playbackController.updateRoute(state.playbackRoute)
    }

    val darkTheme = when (state.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    // Keep system bar icon contrast in sync with the in-app theme override.
    val activity = context as? android.app.Activity
    LaunchedEffect(darkTheme) {
        activity?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    CyberTheme(darkTheme = darkTheme) {
        CyberBackground(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                topBar = {},
                bottomBar = {
                    CyberNavigationBar(
                        items = listOf(
                            CyberNavItem("CREATE", "▣", "创作"),
                            CyberNavItem("VOICES", "◈", "音色"),
                            CyberNavItem("ARCHIVE", "▤", "历史"),
                            CyberNavItem("SYSTEM", "⚙", "设置"),
                        ),
                        selectedIndex = selectedPage,
                        onSelect = { selectedPage = it },
                    )
                },
            ) { padding ->
            when (AppPage.entries[selectedPage]) {
                AppPage.CREATE -> CreateScreen(
                    state = state,
                    padding = padding,
                    onTargetTextChanged = viewModel::onTargetTextChanged,
                    onSpeedChanged = viewModel::onSpeedChanged,
                    onCurrentVoiceClick = { showVoicePicker = true },
                    onSelectProvider = viewModel::onSelectProvider,
                    providerLabel = viewModel::providerLabel,
                    onGenerate = viewModel::generate,
                    onPlaybackRouteChanged = viewModel::onPlaybackRouteChanged,
                    onPlay = {
                        val source = state.lastResult?.audioFile?.let(::File)
                        if (source != null) playbackController.play(source)
                            .onFailure { Toast.makeText(context, "播放失败：${it.message}", Toast.LENGTH_LONG).show() }
                    },
                    onSave = { saveLauncher.launch("shinevoice-${System.currentTimeMillis()}.wav") },
                    onShare = {
                        val source = state.lastResult?.audioFile?.let(::File)
                        if (source != null) {
                            runCatching {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    source,
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "audio/wav"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "分享语音"))
                            }.onFailure {
                                Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(context, "没有可分享的音频。", Toast.LENGTH_SHORT).show()
                        }
                    },
                )

                AppPage.VOICES -> VoicesScreen(
                    state = state,
                    padding = padding,
                    application = application,
                    playbackController = playbackController,
                    viewModel = viewModel,
                )
                AppPage.HISTORY -> HistoryScreen(
                    state = state,
                    padding = padding,
                    application = application,
                    playbackController = playbackController,
                    viewModel = viewModel,
                )
                AppPage.SETTINGS -> SettingsScreen(
                    state = state,
                    padding = padding,
                    viewModel = viewModel,
                    onRefresh = viewModel::refreshModelAndInitialize,
                    onRunStability = viewModel::runTwentyGenerationStabilityTest,
                )
            }
            }
        }
    }

    if (showVoicePicker) {
        VoicePickerSheet(
            voices = state.voices,
            currentId = state.currentVoice?.id,
            onPick = viewModel::setCurrentVoice,
            onDismiss = { showVoicePicker = false },
        )
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    padding: PaddingValues,
    viewModel: MainViewModel,
    onRefresh: () -> Unit,
    onRunStability: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as ShineVoiceApplication
    var showDiagnostics by remember { mutableStateOf(false) }
    var showImportModelHint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.loadMinimaxConfig()
        viewModel.refreshStorageStats()
        viewModel.loadSystemTtsState()
        viewModel.refreshLocalModels()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineSmall) }

        // 模型与服务
        item { SectionTitle("模型与服务") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地模型", fontWeight = FontWeight.Medium)
                    if (state.localModels.isEmpty()) {
                        Text("正在检查本地模型…")
                    } else {
                        state.localModels.forEach { model ->
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = model.active,
                                    onClick = { viewModel.selectLocalModel(model.id) },
                                    enabled = model.installed,
                                )
                                Column {
                                    Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when {
                                            model.installed -> "已安装 · ${model.statusSummary}"
                                            else -> "未安装 · ${model.statusSummary}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (model.installed) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::redetectEverything, enabled = !state.isGenerating && !state.stabilityRunning) {
                            Text("重新检测")
                        }
                        OutlinedButton(onClick = { showImportModelHint = true }) { Text("添加模型") }
                    }
                    Text("云端高清", fontWeight = FontWeight.Medium)
                    Text("状态：${state.minimaxStatus}")
                    Text("服务区域", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.shinevoice.data.settings.MiniMaxRegion.entries.forEach { region ->
                            androidx.compose.material3.FilterChip(
                                selected = state.minimaxRegion == region,
                                onClick = { viewModel.onMinimaxRegionChanged(region) },
                                label = { Text(region.displayName) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.minimaxGroupId,
                        onValueChange = viewModel::onMinimaxGroupIdChanged,
                        label = { Text("Group ID（选填，仅旧版账号需要）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.minimaxApiKey,
                        onValueChange = viewModel::onMinimaxApiKeyChanged,
                        label = { Text("API Key（加密存储，仅本机可见）") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.saveMinimaxConfig { ok, msg ->
                                Toast.makeText(
                                    context,
                                    if (ok) "云端连接正常。" else "云端保存/连接失败：$msg",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }) { Text("保存并测试连接") }
                        OutlinedButton(onClick = viewModel::testMinimaxConnection) { Text("测试连接") }
                        OutlinedButton(onClick = viewModel::clearMinimaxConfig) { Text("清除配置") }
                    }
                    if (state.minimaxClonedVoices.isNotEmpty()) {
                        Text("云端音色：${state.minimaxClonedVoices.joinToString { it.displayName }}")
                    }
                    HorizontalDivider()
                    Text("系统语音", fontWeight = FontWeight.Medium)
                    Text("状态：${state.systemStatus.ifBlank { "未检测" }}")
                    if (state.systemEngines.isNotEmpty()) {
                        Text("语音引擎", style = MaterialTheme.typography.labelMedium)
                        state.systemEngines.forEach { engine ->
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = state.systemSelectedEngine == engine.packageName ||
                                        (state.systemSelectedEngine == null && engine.isSystemDefault),
                                    onClick = { viewModel.selectSystemEngine(engine.packageName) },
                                )
                                Column {
                                    Text(
                                        engine.label + if (engine.isSystemDefault) "（系统默认）" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                    if (state.systemVoices.isNotEmpty()) {
                        Text("中文语音（${state.systemVoices.size} 个）", style = MaterialTheme.typography.labelMedium)
                        state.systemVoices.take(8).forEach { voice ->
                            Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                androidx.compose.material3.RadioButton(
                                    selected = state.systemSelectedVoice == voice.id,
                                    onClick = { viewModel.selectSystemVoice(voice.id) },
                                )
                                Text(voice.displayName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(
                            "未指定语音时使用引擎默认语音",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("在音色库中可为每个音色绑定系统语音。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 外观
        item { SectionTitle("外观") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("主题", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            androidx.compose.material3.FilterChip(
                                selected = state.themeMode == mode,
                                onClick = { viewModel.onThemeModeChanged(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "跟随系统"
                                            ThemeMode.LIGHT -> "亮色"
                                            ThemeMode.DARK -> "暗色"
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // 音频
        item { SectionTitle("音频") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Switch(
                        checked = state.autoSave,
                        onCheckedChange = viewModel::onAutoSaveChanged,
                    )
                    Text("自动保存生成结果到本机", style = MaterialTheme.typography.bodyMedium)
                    Text("输出格式：WAV（16-bit 单声道 24 kHz）", style = MaterialTheme.typography.bodySmall)
                    Text("播放输出", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlaybackRoute.entries.forEach { route ->
                            androidx.compose.material3.FilterChip(
                                selected = state.playbackRoute == route,
                                onClick = { viewModel.onPlaybackRouteChanged(route) },
                                label = { Text(route.displayName) },
                            )
                        }
                    }
                    Text(
                        "听筒模式借用通话音量（播放时按音量键调节），适合私密收听；外放模式插耳机时自动走耳机。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // 存储
        item { SectionTitle("存储") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("占用空间", fontWeight = FontWeight.Medium)
                    Text(state.storageStats?.summary ?: "正在统计…")
                    state.storageStats?.let { stats ->
                        Text("本地模型：${StorageStats.formatBytes(stats.modelsBytes)}")
                        Text("音色参考音频：${StorageStats.formatBytes(stats.voicesBytes)}")
                    }
                    Text("历史记录可在“历史”页多选删除。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 隐私
        item { SectionTitle("隐私") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("请仅使用本人声音或已获得明确授权的声音素材。", fontWeight = FontWeight.Medium)
                    Text("本地生成不联网；云端生成会把文本与参考音频上传到所选云端服务。", style = MaterialTheme.typography.bodyMedium)
                    Text("云端 API Key 使用 Android Keystore 加密保存，不写入源码、日志或提交到 Git。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 高级
        item { SectionTitle("高级") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showDiagnostics = !showDiagnostics }) {
                        Text(if (showDiagnostics) "收起开发与诊断" else "开发与诊断")
                    }
                    if (showDiagnostics) {
                        Text("状态", fontWeight = FontWeight.Medium)
                        application.providerRegistry.snapshots().forEach { snapshot ->
                            Text("${snapshot.displayName}：${snapshot.state.name}", style = MaterialTheme.typography.bodySmall)
                        }
                        state.modelStatus?.let { status ->
                            Text("模型目录：${status.rootPath}", style = MaterialTheme.typography.bodySmall)
                            Text("缺失文件：${status.missingFiles.joinToString().ifEmpty { "无" }}", style = MaterialTheme.typography.bodySmall)
                            Text("完整性校验：${if (status.checksumVerified) "通过" else "未通过"}（SHA-256）", style = MaterialTheme.typography.bodySmall)
                            status.manifest?.let { manifest ->
                                Text("模型：${manifest.modelId} v${manifest.version}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        state.referenceStatus?.let { reference ->
                            Text("参考音频：${reference.referenceAudioPath ?: "-"}（${reference.summary}）", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("架构（ABI）：arm64-v8a / x86_64", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("稳定性测试（连续 20 次本地生成）", fontWeight = FontWeight.Medium)
                        Button(
                            onClick = onRunStability,
                            enabled = state.modelStatus?.ready == true &&
                                !state.isGenerating && !state.stabilityRunning,
                        ) {
                            Text(if (state.stabilityRunning) "测试中 ${state.stabilityCompleted}/20" else "运行 20 次测试")
                        }
                        state.stabilitySummary?.let { summary ->
                            Text("成功率：${summary.successRate} · 平均 ${summary.averageElapsedMs ?: "-"} ms · 最大 ${summary.maxElapsedMs ?: "-"} ms")
                            Text("RTF：平均 ${summary.averageRtf?.let { "%.3f".format(Locale.US, it) } ?: "-"} · 最大 ${summary.maxRtf?.let { "%.3f".format(Locale.US, it) } ?: "-"}")
                            Text("PSS：${summary.memoryBeforePssKb ?: "-"} → ${summary.memoryAfterPssKb ?: "-"} KB · 增量 ${summary.memoryDeltaPssKb ?: "-"} KB")
                            if (summary.failedTasks.isNotEmpty()) {
                                Text("失败任务：${summary.failedTasks.joinToString()}")
                            }
                        }
                        HorizontalDivider()
                        Text("日志（最近 ${state.recentLogs.size} 条）", fontWeight = FontWeight.Medium)
                        OutlinedButton(onClick = viewModel::refreshRecentLogs) { Text("刷新日志") }
                        state.recentLogs.takeLast(12).forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = {
                                val logs = application.logger.recentLogs().joinToString("\n")
                                val diagnostic = buildString {
                                    appendLine("ShineVoice 诊断信息（${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(java.util.Date())}）")
                                    appendLine("生成引擎状态：")
                                    application.providerRegistry.snapshots().forEach { appendLine("  ${it.displayName}=${it.state.name}") }
                                    appendLine("本地模型：${state.localModels.joinToString { it.displayName + if (it.installed) "(已安装)" else "(未安装)" }}")
                                    appendLine("---- 日志 ----")
                                    append(logs)
                                }
                                runCatching {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, diagnostic)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "导出诊断信息"))
                                }.onFailure {
                                    Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
                                }
                            },
                        ) { Text("导出诊断信息") }
                        Text("以上为工程运行数据，普通用户无需关注。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 关于
        item { SectionTitle("关于") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ShineVoice", fontWeight = FontWeight.Medium)
                    Text("版本 0.1.0（原型）")
                    Text("本地优先、云端增强的中文 AI 语音工作台。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (showImportModelHint) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showImportModelHint = false },
            title = { Text("添加本地模型") },
            text = {
                Text(
                    "当前版本内置 ZipVoice 中文声音克隆模型目录的检测。新模型需要先放到手机存储的应用专属目录 " +
                        "Android/data/com.shinevoice.debug/files/models/ 下，然后回到本页点击「重新检测」。模型文件较大，" +
                        "不通过应用内下载（后续版本提供）。",
                )
            },
            confirmButton = {
                Button(onClick = { showImportModelHint = false; viewModel.redetectEverything() }) { Text("去重新检测") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showImportModelHint = false }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}
