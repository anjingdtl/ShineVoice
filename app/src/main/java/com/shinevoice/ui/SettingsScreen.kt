package com.shinevoice.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinevoice.BuildConfig
import com.shinevoice.ShineVoiceApplication
import com.shinevoice.core.audio.PlaybackRoute
import com.shinevoice.data.settings.MiniMaxRegion
import com.shinevoice.data.settings.ThemeMode
import com.shinevoice.ui.cyber.CyberButton
import com.shinevoice.ui.cyber.CyberCard
import com.shinevoice.ui.cyber.CyberChipState
import com.shinevoice.ui.cyber.CyberDialog
import com.shinevoice.ui.cyber.CyberFilterChip
import com.shinevoice.ui.cyber.CyberKV
import com.shinevoice.ui.cyber.CyberOutlinedButton
import com.shinevoice.ui.cyber.CyberPageHeader
import com.shinevoice.ui.cyber.CyberSectionHeader
import com.shinevoice.ui.cyber.CyberStatusChip
import com.shinevoice.ui.cyber.CyberTextField
import com.shinevoice.ui.cyber.CyberType
import com.shinevoice.ui.cyber.LocalCyberColors
import java.util.Locale

/**
 * 设置页：SYSTEM CONTROL / 系统控制台（UI Phase F）。
 *
 * Numbered sections 01~07; every engineering metric lives only inside
 * 06 ADVANCED → 开发与诊断 (SYSTEM DIAGNOSTICS terminal).
 */
@Composable
fun SettingsScreen(
    state: MainUiState,
    padding: PaddingValues,
    viewModel: MainViewModel,
    onRefresh: () -> Unit,
    onRunStability: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as ShineVoiceApplication
    val colors = LocalCyberColors.current
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { CyberPageHeader(title = "SYSTEM CONTROL", code = "系统控制台") }

        // 01 模型与服务 -------------------------------------------------
        item { CyberSectionHeader(index = "01", code = "MODEL & SERVICE", title = "模型与服务") }
        item {
            CyberCard {
                Text("本地模型", fontWeight = FontWeight.Medium, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                if (state.localModels.isEmpty()) {
                    Text("正在检查本地模型……", fontSize = 12.sp, color = colors.textMuted)
                } else {
                    state.localModels.forEach { model ->
                        CyberRadioRow(
                            selected = model.active,
                            enabled = model.installed,
                            title = model.displayName,
                            subtitle = (if (model.installed) "已安装 · " else "未安装 · ") + model.statusSummary,
                            subtitleColor = if (model.installed) colors.textMuted else colors.danger,
                            onClick = { viewModel.selectLocalModel(model.id) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CyberOutlinedButton(
                        text = "重新检测",
                        onClick = viewModel::redetectEverything,
                        enabled = !state.isGenerating && !state.stabilityRunning,
                    )
                    CyberOutlinedButton(text = "添加模型", onClick = { showImportModelHint = true })
                }
            }
        }
        item {
            CyberCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("云端高清（MiniMax）", fontWeight = FontWeight.Medium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    CyberStatusChip(
                        text = when {
                            state.minimaxStatus == "连接正常" -> "ONLINE"
                            state.minimaxStatus == "未配置" -> "未配置"
                            else -> state.minimaxStatus
                        },
                        state = when {
                            state.minimaxStatus == "连接正常" -> CyberChipState.OK
                            state.minimaxStatus == "未配置" -> CyberChipState.OFF
                            else -> CyberChipState.WARN
                        },
                        pulse = state.minimaxStatus == "连接正常",
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("服务区域", style = CyberType.terminalLabel, color = colors.textMuted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniMaxRegion.entries.forEach { region ->
                        CyberFilterChip(
                            selected = state.minimaxRegion == region,
                            label = region.displayName,
                            onClick = { viewModel.onMinimaxRegionChanged(region) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                CyberTextField(
                    value = state.minimaxGroupId,
                    onValueChange = viewModel::onMinimaxGroupIdChanged,
                    label = "Group ID（选填，仅旧版账号需要）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                CyberTextField(
                    value = state.minimaxApiKey,
                    onValueChange = viewModel::onMinimaxApiKeyChanged,
                    label = "API Key（加密存储，仅本机可见）",
                    singleLine = true,
                    password = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CyberButton(text = "保存并测试连接", onClick = {
                        viewModel.saveMinimaxConfig { ok, msg ->
                            Toast.makeText(
                                context,
                                if (ok) "云端连接正常。" else "云端保存/连接失败：$msg",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    })
                    CyberOutlinedButton(text = "测试连接", onClick = viewModel::testMinimaxConnection)
                    CyberOutlinedButton(text = "清除配置", onClick = viewModel::clearMinimaxConfig, tint = colors.danger)
                }
                if (state.minimaxClonedVoices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "云端音色：${state.minimaxClonedVoices.joinToString { it.displayName }}",
                        fontSize = 12.sp,
                        color = colors.textMuted,
                    )
                }
            }
        }
        item {
            CyberCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("系统语音", fontWeight = FontWeight.Medium, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    CyberStatusChip(
                        text = state.systemStatus.ifBlank { "未检测" },
                        state = if (state.systemStatus.contains("已选择") || state.systemStatus.contains("已就绪")) {
                            CyberChipState.OK
                        } else {
                            CyberChipState.INFO
                        },
                    )
                }
                if (state.systemEngines.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("语音引擎", style = CyberType.terminalLabel, color = colors.textMuted)
                    state.systemEngines.forEach { engine ->
                        CyberRadioRow(
                            selected = state.systemSelectedEngine == engine.packageName ||
                                (state.systemSelectedEngine == null && engine.isSystemDefault),
                            enabled = true,
                            title = engine.label + if (engine.isSystemDefault) "（系统默认）" else "",
                            onClick = { viewModel.selectSystemEngine(engine.packageName) },
                        )
                    }
                }
                if (state.systemVoices.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "中文语音（${state.systemVoices.size} 个音色）",
                        style = CyberType.terminalLabel,
                        color = colors.textMuted,
                    )
                    state.systemVoices.forEach { voice ->
                        CyberRadioRow(
                            selected = state.systemSelectedVoice == voice.id,
                            enabled = true,
                            title = voice.displayName,
                            onClick = { viewModel.selectSystemVoice(voice.id) },
                        )
                    }
                    Text(
                        "未指定语音时使用引擎默认语音；同一音色优先展示离线版，联网版仅在无离线实现时出现。",
                        fontSize = 11.sp,
                        color = colors.textMuted,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("在音色库中可为每个音色绑定系统语音。", fontSize = 11.sp, color = colors.textMuted)
            }
        }

        // 02 外观 -------------------------------------------------------
        item { CyberSectionHeader(index = "02", code = "APPEARANCE", title = "外观") }
        item {
            CyberCard {
                Text("主题", fontWeight = FontWeight.Medium, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        CyberFilterChip(
                            selected = state.themeMode == mode,
                            label = when (mode) {
                                ThemeMode.SYSTEM -> "跟随系统"
                                ThemeMode.LIGHT -> "亮色"
                                ThemeMode.DARK -> "暗色"
                            },
                            onClick = { viewModel.onThemeModeChanged(mode) },
                        )
                    }
                }
                Text("暗色为默认赛博朋克主题；亮色保持同一设计语言。", fontSize = 11.sp, color = colors.textMuted)
            }
        }

        // 03 音频 -------------------------------------------------------
        item { CyberSectionHeader(index = "03", code = "AUDIO", title = "音频") }
        item {
            CyberCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动保存生成结果", fontWeight = FontWeight.Medium, color = colors.textPrimary)
                        Text("输出格式：WAV（16-bit 单声道 24 kHz）", fontSize = 11.sp, color = colors.textMuted)
                    }
                    Switch(
                        checked = state.autoSave,
                        onCheckedChange = viewModel::onAutoSaveChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.accent,
                            checkedTrackColor = colors.cyanDim,
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.outline,
                        ),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("播放输出", style = CyberType.terminalLabel, color = colors.textMuted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaybackRoute.entries.forEach { route ->
                        CyberFilterChip(
                            selected = state.playbackRoute == route,
                            label = route.displayName,
                            onClick = { viewModel.onPlaybackRouteChanged(route) },
                        )
                    }
                }
                Text(
                    "听筒模式借用通话音量（播放时按音量键调节），适合私密收听；外放模式插耳机时自动走耳机。",
                    fontSize = 11.sp,
                    color = colors.textMuted,
                )
            }
        }

        // 04 存储 -------------------------------------------------------
        item { CyberSectionHeader(index = "04", code = "STORAGE", title = "存储") }
        item {
            CyberCard {
                Text("占用空间", fontWeight = FontWeight.Medium, color = colors.textPrimary)
                Spacer(Modifier.height(8.dp))
                Text(state.storageStats?.summary ?: "正在统计……", fontSize = 13.sp, color = colors.textPrimary)
                state.storageStats?.let { stats ->
                    CyberKV("MODELS", StorageStats.formatBytes(stats.modelsBytes))
                    CyberKV("VOICES", StorageStats.formatBytes(stats.voicesBytes))
                }
                Text("历史记录可在「历史」页多选删除。", fontSize = 11.sp, color = colors.textMuted)
            }
        }

        // 05 隐私 -------------------------------------------------------
        item { CyberSectionHeader(index = "05", code = "PRIVACY", title = "隐私") }
        item {
            CyberCard {
                Text("请仅使用本人声音或已获得明确授权的声音素材。", fontWeight = FontWeight.Medium, color = colors.textPrimary)
                Spacer(Modifier.height(6.dp))
                Text("本地生成不联网；云端生成会把文本与参考音频上传到所选云端服务。", fontSize = 12.sp, color = colors.textMuted)
                Text("云端 API Key 使用 Android Keystore 加密保存，不写入源码、日志或提交到 Git。", fontSize = 11.sp, color = colors.textMuted)
            }
        }

        // 06 高级 -------------------------------------------------------
        item { CyberSectionHeader(index = "06", code = "ADVANCED", title = "高级") }
        item {
            CyberCard {
                CyberOutlinedButton(
                    text = if (showDiagnostics) "收起开发与诊断 ▴" else "开发与诊断 ▾",
                    onClick = { showDiagnostics = !showDiagnostics },
                )
                if (showDiagnostics) {
                    Spacer(Modifier.height(10.dp))
                    Text("SYSTEM DIAGNOSTICS", style = CyberType.sectionCode, color = colors.cyan)
                    Spacer(Modifier.height(8.dp))
                    application.providerRegistry.snapshots().forEach { snapshot ->
                        CyberKV(
                            snapshot.displayName,
                            snapshot.state.name,
                            valueColor = when (snapshot.state.name) {
                                "READY" -> colors.success
                                "ERROR" -> colors.danger
                                else -> colors.textMuted
                            },
                        )
                    }
                    state.modelStatus?.let { status ->
                        CyberKV("MODEL DIR", status.rootPath, maxLines = 2)
                        CyberKV("MISSING", status.missingFiles.joinToString().ifEmpty { "无" })
                        CyberKV("CHECKSUM", if (status.checksumVerified) "VERIFIED (SHA-256)" else "FAILED", maxLines = 1)
                        status.manifest?.let { manifest ->
                            CyberKV("MODEL", "${manifest.modelId} v${manifest.version}")
                        }
                    }
                    state.referenceStatus?.let { reference ->
                        CyberKV("REFERENCE", "${reference.referenceAudioPath ?: "-"}", maxLines = 2)
                        CyberKV("REF STATE", reference.summary, maxLines = 2)
                    }
                    CyberKV("ABI", "arm64-v8a / x86_64")
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("稳定性测试（连续 20 次本地生成）", fontWeight = FontWeight.Medium, color = colors.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    CyberButton(
                        text = if (state.stabilityRunning) "测试中 ${state.stabilityCompleted}/20" else "运行 20 次测试",
                        onClick = onRunStability,
                        enabled = state.modelStatus?.ready == true &&
                            !state.isGenerating && !state.stabilityRunning,
                    )
                    state.stabilitySummary?.let { summary ->
                        Spacer(Modifier.height(6.dp))
                        CyberKV("SUCCESS", summary.successRate, valueColor = colors.success)
                        CyberKV("ELAPSED", "avg ${summary.averageElapsedMs ?: "-"} ms · max ${summary.maxElapsedMs ?: "-"} ms")
                        CyberKV(
                            "RTF",
                            "avg ${summary.averageRtf?.let { "%.3f".format(Locale.US, it) } ?: "-"} · " +
                                "max ${summary.maxRtf?.let { "%.3f".format(Locale.US, it) } ?: "-"}",
                        )
                        CyberKV("PSS", "${summary.memoryBeforePssKb ?: "-"} → ${summary.memoryAfterPssKb ?: "-"} KB (Δ${summary.memoryDeltaPssKb ?: "-"})")
                        if (summary.failedTasks.isNotEmpty()) {
                            CyberKV("FAILED", summary.failedTasks.joinToString(), valueColor = colors.danger, maxLines = 2)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("LOG（最近 ${state.recentLogs.size} 条）", style = CyberType.sectionCode, color = colors.cyan)
                    Spacer(Modifier.height(6.dp))
                    CyberOutlinedButton(text = "刷新日志", onClick = viewModel::refreshRecentLogs)
                    state.recentLogs.takeLast(12).forEach { line ->
                        Text(
                            line,
                            style = CyberType.terminalLabel,
                            color = colors.textMuted,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    CyberOutlinedButton(
                        text = "导出诊断信息",
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
                    )
                    Text("以上为工程运行数据，普通用户无需关注。", fontSize = 11.sp, color = colors.textMuted)
                }
            }
        }

        // 07 关于 -------------------------------------------------------
        item { CyberSectionHeader(index = "07", code = "ABOUT", title = "关于") }
        item {
            CyberCard {
                Text("SHINEVOICE", style = CyberType.sectionCode, color = colors.accent)
                Spacer(Modifier.height(4.dp))
                Text("版本 ${BuildConfig.VERSION_NAME}（正式版）", fontSize = 13.sp, color = colors.textPrimary)
                Text("本地优先、云端增强的中文 AI 语音工作台。", fontSize = 11.sp, color = colors.textMuted)
            }
        }
    }

    if (showImportModelHint) {
        CyberDialog(
            onDismissRequest = { showImportModelHint = false },
            title = "添加本地模型",
            code = "IMPORT MODEL",
            actions = {
                CyberOutlinedButton(text = "知道了", onClick = { showImportModelHint = false })
                CyberButton(
                    text = "去重新检测",
                    onClick = { showImportModelHint = false; viewModel.redetectEverything() },
                )
            },
        ) {
            Text(
                "标准版已内置 ZipVoice 中文声音克隆模型：首次启动会自动解包到本机（约 200 MB），无需下载、离线可用。" +
                    "如需更换其他模型，可将其放到手机存储的应用专属目录 Android/data/com.shinevoice.debug/files/models/ 下，" +
                    "然后回到本页点击「重新检测」。",
                fontSize = 12.sp,
                color = colors.textPrimary,
            )
        }
    }
}

/** Terminal radio row: neon square indicator + title/subtitle. */
@Composable
private fun CyberRadioRow(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    subtitleColor: Color? = null,
) {
    val colors = LocalCyberColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .drawBehind { drawRect(if (selected) colors.accent else colors.outlineStrong) }
                .padding(3.dp)
                .drawBehind { if (selected) drawRect(colors.onAccent) },
        )
        Column {
            Text(
                title,
                fontSize = 13.sp,
                color = if (enabled) colors.textPrimary else colors.textMuted,
            )
            subtitle?.let {
                Text(it, style = CyberType.terminalLabel, color = subtitleColor ?: colors.textMuted, maxLines = 2)
            }
        }
    }
}
