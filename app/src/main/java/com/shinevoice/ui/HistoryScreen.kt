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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.core.content.FileProvider
import com.shinevoice.ShineVoiceApplication
import com.shinevoice.core.storage.AudioExporter
import com.shinevoice.core.storage.AudioPlaybackController
import com.shinevoice.data.db.GenerationHistoryEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var selectMode by remember { mutableStateOf(false) }
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("生成历史", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (selectMode) {
                OutlinedButton(onClick = {
                    viewModel.setHistorySelectAll(true)
                }) { Text("全选") }
                OutlinedButton(onClick = { selectMode = false; viewModel.exitHistorySelection() }) {
                    Text("完成")
                }
            } else {
                Button(onClick = { selectMode = true }) { Text("选择") }
            }
        }
        if (selectMode) {
            val count = state.historySelection.size
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = viewModel::deleteSelectedHistory, enabled = count > 0) {
                    Text("删除选中（$count）")
                }
                OutlinedButton(onClick = ::shareSelected, enabled = count > 0) {
                    Text("分享选中")
                }
                OutlinedButton(onClick = ::exportSelectedAsZip, enabled = count > 0) {
                    Text("导出 ZIP")
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.history.isEmpty()) {
                item { Text("还没有生成记录，去创作页生成一段语音吧。") }
            } else {
                groups.forEach { (dateKey, items) ->
                    item(key = "header-$dateKey") {
                        DateSectionHeader(
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
                            HistoryRow(
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
        today -> "今天"
        yesterday -> "昨天"
        else -> {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(dateKey) ?: return dateKey
            val sameYear = SimpleDateFormat("yyyy", Locale.CHINA).format(date) ==
                SimpleDateFormat("yyyy", Locale.CHINA).format(Date())
            SimpleDateFormat(if (sameYear) "MM月dd日" else "yyyy年MM月dd日", Locale.CHINA).format(date)
        }
    }
}

@Composable
private fun DateSectionHeader(
    title: String,
    expanded: Boolean,
    count: Int,
    onToggle: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "▾ " else "▸ ",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("$title", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$count 条", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryRow(
    item: GenerationHistoryEntity,
    selectMode: Boolean,
    selected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(
                    item.inputText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                )
                Text(
                    buildString {
                        append(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(item.createdAt)))
                        if (item.success && item.durationMs != null) {
                            append(" · 时长 ${formatDuration(item.durationMs)}")
                        }
                        if (isPlaying) append(" · 播放中")
                    } + if (item.success) "" else " · 生成失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            if (!selectMode && item.success) {
                IconButton(onClick = onClick) { Text("▶") }
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(title: String, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("♪", style = MaterialTheme.typography.titleMedium)
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onStop) { Text("停止") }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs + 500) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.US, minutes, seconds)
}