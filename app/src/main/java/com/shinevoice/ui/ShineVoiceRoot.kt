package com.shinevoice.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinevoice.ShineVoiceApplication
import com.shinevoice.core.storage.AudioPlaybackController
import com.shinevoice.data.db.GenerationHistoryEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
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

    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { TopAppBar(title = { Text("ShineVoice") }) },
            bottomBar = {
                NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                    AppPage.entries.forEach { page ->
                        NavigationBarItem(
                            selected = selectedPage == page.ordinal,
                            onClick = { selectedPage = page.ordinal },
                            icon = { Text(page.title.take(1)) },
                            label = { Text(page.title) },
                        )
                    }
                }
            },
        ) { padding ->
            when (AppPage.entries[selectedPage]) {
                AppPage.CREATE -> CreateScreen(
                    state = state,
                    padding = padding,
                    onTargetTextChanged = viewModel::onTargetTextChanged,
                    onSpeedChanged = viewModel::onSpeedChanged,
                    onReferenceTextChanged = viewModel::onCurrentReferenceTextChanged,
                    onCurrentVoiceClick = { showVoicePicker = true },
                    onGenerate = viewModel::generate,
                    onPlay = {
                        val source = state.lastResult?.audioFile?.let(::File)
                        if (source != null) playbackController.play(source)
                            .onFailure { Toast.makeText(context, "播放失败：${it.message}", Toast.LENGTH_LONG).show() }
                    },
                    onSave = { saveLauncher.launch("shinevoice-${System.currentTimeMillis()}.wav") },
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
                    onRefresh = viewModel::refreshModelAndInitialize,
                    onRunStability = viewModel::runTwentyGenerationStabilityTest,
                )
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
@OptIn(ExperimentalMaterial3Api::class)
private fun CreateScreen(
    state: MainUiState,
    padding: PaddingValues,
    onTargetTextChanged: (String) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onReferenceTextChanged: (String) -> Unit,
    onCurrentVoiceClick: () -> Unit,
    onGenerate: () -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("本地优先的中文声音克隆", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Card(onClick = onCurrentVoiceClick, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("当前音色", style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.currentVoice?.displayName ?: "默认参考音色",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("生成方式：本地生成 · 点击切换音色", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.targetText,
                onValueChange = onTargetTextChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入需要生成的中文文本") },
                minLines = 3,
                enabled = !state.isGenerating && !state.stabilityRunning,
            )
        }
        item {
            Text(
                "语速：${"%.2f".format(Locale.US, state.speed)}x",
                style = MaterialTheme.typography.labelMedium,
            )
            androidx.compose.material3.Slider(
                value = state.speed,
                onValueChange = onSpeedChanged,
                valueRange = 0.5f..2.0f,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isGenerating && !state.stabilityRunning,
            )
        }
        item {
            OutlinedTextField(
                value = state.currentVoice?.referenceText ?: "",
                onValueChange = onReferenceTextChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("参考文本（当前音色，需与参考音频一致）") },
                minLines = 2,
                enabled = !state.isGenerating && !state.stabilityRunning,
            )
        }
        item {
            Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isGenerating && !state.stabilityRunning &&
                    state.modelStatus?.ready == true && state.referenceStatus?.ready == true,
            ) {
                Text(if (state.isGenerating) "生成中…" else "生成语音")
            }
        }
        item {
            state.message?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary)
            }
        }
        state.lastResult?.let { result ->
            item { GenerationResultCard(result, onPlay, onSave) }
        }
        item {
            Text(
                "请仅使用本人声音或已获得明确授权的声音素材。Phase 1 固定使用本地参考音频，不上传网络。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GenerationResultCard(
    result: com.shinevoice.domain.tts.TtsResult,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (result.success) "生成成功" else "生成失败", fontWeight = FontWeight.Bold)
            Text("生成方式：本地生成")
            Text("耗时：${result.elapsedMs} ms · 音频长度：${if (result.durationMs != null) "${result.durationMs} ms" else "-"}")
            result.error?.let { Text("错误：${it.userMessage}") }
            if (result.success) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPlay) { Text("播放") }
                    Button(onClick = onSave) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onRunStability: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("设置 / Debug", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ZipVoice 模型", fontWeight = FontWeight.Bold)
                    Text(state.modelStatus?.summary ?: "正在检查…")
                    Text(state.modelStatus?.rootPath ?: "")
                    Text("ABI：arm64-v8a / x86_64 · Provider：CPU", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onRefresh, enabled = !state.isGenerating && !state.stabilityRunning) {
                        Text("重新检测并加载")
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("稳定性测试", fontWeight = FontWeight.Bold)
                    Text("连续 20 次真实 Native ZipVoice 生成，串行执行并写入历史。")
                    Button(
                        onClick = onRunStability,
                        enabled = state.modelStatus?.ready == true && state.referenceStatus?.ready == true &&
                            !state.isGenerating && !state.stabilityRunning,
                    ) {
                        Text(if (state.stabilityRunning) "测试中 ${state.stabilityCompleted}/20" else "运行 20 次测试")
                    }
                    state.stabilitySummary?.let { summary ->
                        Text("成功率：${summary.successRate}")
                        Text("平均：${summary.averageElapsedMs ?: "-"} ms · 最大：${summary.maxElapsedMs ?: "-"} ms")
                        Text("RTF：平均 ${summary.averageRtf?.let { "%.3f".format(Locale.US, it) } ?: "-"} · 最大 ${summary.maxRtf?.let { "%.3f".format(Locale.US, it) } ?: "-"}")
                        Text("PSS：${summary.memoryBeforePssKb ?: "-"} → ${summary.memoryAfterPssKb ?: "-"} KB · 增量 ${summary.memoryDeltaPssKb ?: "-"} KB")
                        if (summary.failedTasks.isNotEmpty()) {
                            Text("失败任务：${summary.failedTasks.joinToString()}")
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Spacer(Modifier.height(2.dp))
            Text("Phase 0/1 范围内：Android System TTS、MiniMax、ASR 和完整音色管理尚未实现。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
