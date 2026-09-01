package com.shinevoice.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.shinevoice.ShineVoiceApplication
import com.shinevoice.core.storage.AudioExporter
import com.shinevoice.core.storage.AudioPlaybackController
import com.shinevoice.data.db.GenerationHistoryEntity
import com.shinevoice.ui.cyber.CyberButton
import com.shinevoice.ui.cyber.CyberCard
import com.shinevoice.ui.cyber.CyberChipState
import com.shinevoice.ui.cyber.CyberDialog
import com.shinevoice.ui.cyber.CyberOutlinedButton
import com.shinevoice.ui.cyber.CyberPageHeader
import com.shinevoice.ui.cyber.CyberType
import com.shinevoice.ui.cyber.LocalCyberColors
import com.shinevoice.ui.cyber.PulsingDot
import com.shinevoice.ui.cyber.formatDurationClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 历史页：Audio Archive / 语音档案记录（UI Phase E）。
 *
 * Date groups fold like archive drawers; rows are playback-ready dossiers.
 * Multi-select mode shows SELECTED: NN and batch delete/share/ZIP with a
 * mandatory confirmation before deletion.
 */
@Composable
fun HistoryScreen(
    state: MainUiState,
    padding: PaddingValues,
    application: ShineVoiceApplication,
    playbackController: AudioPlaybackController,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalCyberColors.current
    var selectMode by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var expandedDates by remember { mutableStateOf(emptySet<String>()) }
    val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    val groups = remember(state.history) { groupByDate(state.history) }

    LaunchedEffect(groups.keys) {
        if (expandedDates.isEmpty() && groups.keys.any { it == todayKey }) {
            expandedDates = setOf(todayKey)
        }
    }

    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        val zip = application.zippedExport
        if (destination == null || zip == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(destination)?.use { out ->
                zip.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("无法打开导出位置")
            Toast.makeText(context, "ZIP 已导出。", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Toast.makeText(context, "导出失败：${error.message}", Toast.LENGTH_LONG).show()
        } finally {
            application.zippedExport = null
        }
    }

    fun playItem(item: GenerationHistoryEntity) {
        val file = item.audioPath?.let(::File)
        if (file == null || !file.isFile) {
            Toast.makeText(context, "音频文件不存在（可能已被删除）。", Toast.LENGTH_SHORT).show()
            return
        }
        playbackController.play(file)
            .onSuccess { viewModel.setNowPlaying(item.taskId, item.inputText) }
            .onFailure { Toast.makeText(context, "播放失败：${it.message}", Toast.LENGTH_LONG).show() }
    }

    fun shareSelected() {
        val selected = state.history.filter { it.taskId in state.historySelection && it.audioPath != null }
        if (selected.isEmpty()) {
            Toast.makeText(context, "请先选择要分享的内容。", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = selected.mapNotNull { item ->
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(item.audioPath!!),
                )
            }.getOrNull()
        }
        if (uris.isEmpty()) {
            Toast.makeText(context, "没有可分享的音频文件。", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享语音"))
    }

    fun exportSelectedAsZip() {
        val selected = state.history.filter { it.taskId in state.historySelection && it.audioPath != null }
        if (selected.isEmpty()) {
            Toast.makeText(context, "请先选择要导出的内容。", Toast.LENGTH_SHORT).show()
            return
        }
        val target = File(context.filesDir, "exports/shinevoice-${System.currentTimeMillis()}.zip")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AudioExporter.exportAsZip(selected.map { it.inputText to File(it.audioPath!!) }, target)
            }
            result.onSuccess {
                application.zippedExport = it
                exportZipLauncher.launch("shinevoice-${System.currentTimeMillis()}.zip")
            }.onFailure {
                Toast.makeText(context, "打包失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CyberPageHeader(
                title = "AUDIO ARCHIVE",
                code = "语音档案记录",
                modifier = Modifier.weight(1f),
                trailing = {
                    if (selectMode) {
                        CyberOutlinedButton(text = "完成", onClick = {
                            selectMode = false
                            viewModel.exitHistorySelection()
                        })
                    } else {
                        CyberOutlinedButton(text = "选择", onClick = { selectMode = true })
                    }
                },
            )
        }

        if (selectMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "SELECTED: %02d".format(state.historySelection.size),
                    style = CyberType.terminalValue,
                    color = colors.accent,
                )
                Spacer(Modifier.weight(1f))
                CyberOutlinedButton(text = "全选", onClick = { viewModel.setHistorySelectAll(true) })
                CyberOutlinedButton(
                    text = "删除",
                    onClick = { confirmDelete = true },
                    enabled = state.historySelection.isNotEmpty(),
                    tint = colors.danger,
                )
                CyberOutlinedButton(
                    text = "分享",
                    onClick = ::shareSelected,
                    enabled = state.historySelection.isNotEmpty(),
                )
                CyberOutlinedButton(
                    text = "ZIP",
                    onClick = ::exportSelectedAsZip,
                    enabled = state.historySelection.isNotEmpty(),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.history.isEmpty()) {
                item {
                    CyberCard {
                        Text("还没有生成记录，去创作页生成一段语音吧。", color = colors.textMuted, fontSize = 13.sp)
                    }
                }
            } else {
                groups.forEach { (dateKey, items) ->
                    item(key = "header-$dateKey") {
                        ArchiveDateHeader(
                            title = dateTitle(dateKey),
                            expanded = dateKey in expandedDates,
                            count = items.size,
                            onToggle = {
                                expandedDates = if (dateKey in expandedDates) {
                                    expandedDates - dateKey
                                } else {
                                    expandedDates + dateKey
                                }
                            },
                        )
                    }
                    if (dateKey in expandedDates) {
                        items(items, key = { it.taskId }) { item ->
                            ArchiveRow(
                                item = item,
                                selectMode = selectMode,
                                selected = item.taskId in state.historySelection,
                                isPlaying = item.taskId == state.nowPlayingTaskId,
                                onClick = {
                                    if (selectMode) viewModel.toggleHistorySelect(item.taskId)
                                    else playItem(item)
                                },
                                onToggleSelect = { viewModel.toggleHistorySelect(item.taskId) },
                            )
                        }
                    }
                }
            }
        }

        state.nowPlayingTaskId?.let { playingId ->
            MiniPlayerBar(
                title = state.nowPlayingTitle ?: "正在播放",
                onStop = {
                    playbackController.stop()
                    viewModel.setNowPlaying(null, null)
                },
            )
        }
    }

    if (confirmDelete) {
        CyberDialog(
            onDismissRequest = { confirmDelete = false },
            title = "删除生成记录",
            code = "DELETE RECORDS",
            actions = {
                CyberOutlinedButton(text = "取消", onClick = { confirmDelete = false })
                CyberButton(
                    text = "删除",
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteSelectedHistory()
                        selectMode = false
                    },
                )
            },
        ) {
            Text(
                "将删除选中的 ${state.historySelection.size} 条记录及其音频文件，删除后无法恢复。确定删除吗？",
                fontSize = 13.sp,
                color = colors.textPrimary,
            )
        }
    }
}

private fun groupByDate(history: List<GenerationHistoryEntity>): LinkedHashMap<String, List<GenerationHistoryEntity>> {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    val map = LinkedHashMap<String, MutableList<GenerationHistoryEntity>>()
    history.forEach { item ->
        val key = format.format(Date(item.createdAt))
        map.getOrPut(key) { mutableListOf() }.add(item)
    }
    return LinkedHashMap(map.mapValues { it.value.toList() })
}

private fun dateTitle(dateKey: String): String {
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(System.currentTimeMillis() - 86400000L))
    return when (dateKey) {
        today -> "TODAY"
        yesterday -> "YESTERDAY"
        else -> {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(dateKey) ?: return dateKey
            val sameYear = SimpleDateFormat("yyyy", Locale.CHINA).format(date) ==
                SimpleDateFormat("yyyy", Locale.CHINA).format(Date())
            SimpleDateFormat(if (sameYear) "MM月dd日" else "yyyy年MM月dd日", Locale.CHINA).format(date)
        }
    }
}

/** Archive drawer header: `TODAY // 12 ▾` in terminal mono. */
@Composable
private fun ArchiveDateHeader(
    title: String,
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit,
) {
    val colors = LocalCyberColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = CyberType.sectionCode,
            color = colors.cyan,
        )
        Spacer(Modifier.padding(4.dp))
        Text(
            "// %02d".format(count),
            style = CyberType.sectionCode,
            color = colors.textMuted,
        )
        Text(
            if (expanded) "  ▾" else "  ▸",
            style = CyberType.sectionCode,
            color = colors.accent,
        )
    }
}

/** One archived generation: play glyph, text summary, mono meta line. */
@Composable
private fun ArchiveRow(
    item: GenerationHistoryEntity,
    selectMode: Boolean,
    selected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    val colors = LocalCyberColors.current
    CyberCard(highlighted = isPlaying) {
        Row(
            modifier = Modifier.clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.accent,
                        checkmarkColor = colors.onAccent,
                        uncheckedColor = colors.outlineStrong,
                    ),
                )
            }
            Text(
                "▶",
                style = CyberType.terminalValue,
                color = if (item.success) colors.accent else colors.danger,
            )
            Spacer(Modifier.padding(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.inputText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(item.createdAt)),
                        style = CyberType.terminalLabel,
                        color = colors.textMuted,
                    )
                    if (item.success && item.durationMs != null) {
                        Text(
                            formatDurationClock(item.durationMs),
                            style = CyberType.terminalLabel,
                            color = colors.textMuted,
                        )
                    }
                    if (isPlaying) {
                        PulsingDot(colors.cyan, diameter = 5.dp)
                        Text("播放中", style = CyberType.terminalLabel, color = colors.cyan)
                    }
                    if (!item.success) {
                        Text("生成失败", style = CyberType.terminalLabel, color = colors.danger)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(title: String, onStop: () -> Unit) {
    val colors = LocalCyberColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PulsingDot(colors.accent)
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = CyberType.terminalValue,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        CyberOutlinedButton(text = "停止", onClick = onStop)
    }
}
