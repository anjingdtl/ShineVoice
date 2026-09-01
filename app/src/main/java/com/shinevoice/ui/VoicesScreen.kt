package com.shinevoice.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.shinevoice.ShineVoiceApplication
import com.shinevoice.core.audio.TARGET_SAMPLE_RATE
import com.shinevoice.core.audio.VoiceRecorder
import com.shinevoice.core.storage.AudioPlaybackController
import com.shinevoice.data.db.VoiceProfileEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(
    state: MainUiState,
    padding: PaddingValues,
    application: ShineVoiceApplication,
    playbackController: AudioPlaybackController,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var profileReferenceText by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var recordingId by remember { mutableStateOf<String?>(null) }
    var pendingImportId by remember { mutableStateOf<String?>(null) }
    var pendingRecordId by remember { mutableStateOf<String?>(null) }
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }

    fun checkPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val profileId = pendingImportId
        pendingImportId = null
        if (uri == null || profileId == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dir = application.voiceProfileManager.profileDir(profileId)
            val result = withContext(Dispatchers.IO) {
                application.audioImporter.import(uri, dir)
            }
            result.onSuccess { reference ->
                viewModel.attachVoiceAudio(profileId, reference.absolutePath, null)
                Toast.makeText(context, "音频已导入并标准化为 ${TARGET_SAMPLE_RATE} Hz WAV。", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(context, "导入失败：${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startRecording(profileId: String) {
        val dir = application.voiceProfileManager.profileDir(profileId)
        dir.mkdirs()
        val wav = File(dir, "reference.wav")
        val newRecorder = VoiceRecorder(wav)
        recorder = newRecorder
        recordingId = profileId
        if (newRecorder.start()) {
            Toast.makeText(context, "正在录音，再次点击停止。", Toast.LENGTH_SHORT).show()
        } else {
            recordingId = null
            recorder = null
            Toast.makeText(context, newRecorder.lastError() ?: "录音启动失败，请检查权限。", Toast.LENGTH_LONG).show()
        }
    }

    fun stopRecording(profileId: String) {
        val ok = recorder?.stop() == true
        recorder = null
        recordingId = null
        if (ok) {
            val wav = File(application.voiceProfileManager.profileDir(profileId), "reference.wav")
            viewModel.attachVoiceAudio(profileId, wav.absolutePath, null)
            Toast.makeText(context, "录音完成并保存为参考音频。", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "录音太短或失败，请重试。", Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingRecordId?.let { startRecording(it) }
            pendingImportId?.let { importLauncher.launch("audio/*") }
        } else {
            Toast.makeText(context, "需要录音权限才能录制或校验音频。", Toast.LENGTH_SHORT).show()
        }
        pendingRecordId = null
        pendingImportId = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("我的音色", style = MaterialTheme.typography.headlineSmall)
            Text("管理你的声音库：录音 / 导入参考音频，填写参考文本，绑定本地或云端生成。", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Text("＋ 创建音色")
            }
        }
        if (state.voices.isEmpty()) {
            item { Text("暂无音色，点击右下角创建。") }
        } else {
            items(state.voices, key = { it.id }) { profile ->
                VoiceProfileCard(
                    profile = profile,
                    isCurrent = profile.id == state.currentVoice?.id,
                    expanded = profile.id == expandedId,
                    onExpand = { expandedId = if (expandedId == profile.id) null else profile.id },
                    onSetCurrent = { viewModel.setCurrentVoice(profile.id) },
                    onRename = { newName ->
                        if (newName.isNotBlank()) viewModel.renameVoice(profile.id, newName)
                    },
                    onDelete = { viewModel.deleteVoice(profile.id) },
                    onPlayReference = {
                        val file = profile.referenceAudioPath?.let(::File)
                        if (file != null && file.isFile) {
                            playbackController.play(file)
                                .onFailure { Toast.makeText(context, "试听失败：${it.message}", Toast.LENGTH_LONG).show() }
                        } else {
                            Toast.makeText(context, "该音色还没有参考音频。", Toast.LENGTH_SHORT).show()
                        }
                    },
                    recording = profile.id == recordingId,
                    onToggleRecord = {
                        if (profile.id == recordingId) {
                            stopRecording(profile.id)
                        } else if (!checkPermission()) {
                            pendingRecordId = profile.id
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            startRecording(profile.id)
                        }
                    },
                    onImport = {
                        pendingImportId = profile.id
                        if (checkPermission()) importLauncher.launch("audio/*")
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onEditReferenceText = { viewModel.updateVoiceReferenceText(profile.id, it) },
                    cloudCloning = state.cloudCloning,
                    onCloneToCloud = {
                        viewModel.cloneVoiceToCloud(profile.id) { ok, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("创建音色") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("音色名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = profileReferenceText,
                        onValueChange = { profileReferenceText = it },
                        label = { Text("参考文本（与录音内容一致，可稍后填写）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "创建后请录音或导入一段清晰的参考音频（推荐 5~15 秒），参考文本需与音频内容一致。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = profileName.isNotBlank(),
                    onClick = {
                        viewModel.createVoice(
                            displayName = profileName.trim(),
                            referenceText = profileReferenceText.takeIf { it.isNotBlank() },
                        )
                        creating = false
                        profileName = ""
                        profileReferenceText = ""
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                OutlinedButton(onClick = { creating = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun VoiceProfileCard(
    profile: VoiceProfileEntity,
    isCurrent: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onSetCurrent: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onPlayReference: () -> Unit,
    recording: Boolean,
    onToggleRecord: () -> Unit,
    onImport: () -> Unit,
    onEditReferenceText: (String) -> Unit,
    cloudCloning: Boolean,
    onCloneToCloud: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (isCurrent) "${profile.displayName}（当前）" else profile.displayName,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (recording) {
                    Text("● 录音中", color = MaterialTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BindingBadge(label = "本地", active = profile.hasLocalBinding)
                BindingBadge(label = "云端", active = profile.hasCloudBinding)
                BindingBadge(label = "系统", active = profile.hasSystemBinding)
            }
            profile.lastUsedAt.takeIf { it > 0 }?.let { lastUsed ->
                Text(
                    "最近使用：${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(lastUsed))}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSetCurrent, enabled = !isCurrent) { Text("设为当前") }
                OutlinedButton(onClick = onPlayReference) { Text("试听") }
                OutlinedButton(onClick = onExpand) { Text(if (expanded) "收起" else "编辑") }
            }
            if (expanded) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onToggleRecord) {
                            Text(if (recording) "停止录音" else "录音")
                        }
                        OutlinedButton(onClick = onImport) { Text("导入音频") }
                        OutlinedButton(onClick = { onRename(profile.displayName) }) { Text("重命名") }
                    }
                    OutlinedTextField(
                        value = profile.referenceText ?: "",
                        onValueChange = onEditReferenceText,
                        label = { Text("参考文本（与音频内容一致）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    profile.referenceAudioPath?.let { path ->
                    val exists = File(path).isFile
                    Text(
                        if (exists) "本地参考音频已就绪" else "本地参考音频缺失，请重新录音或导入",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                    if (!profile.isDefault) {
                        OutlinedButton(onClick = onDelete) { Text("删除音色") }
                    }
                    if (profile.referenceAudioPath != null) {
                        OutlinedButton(onClick = onCloneToCloud, enabled = !cloudCloning) {
                            Text(if (cloudCloning) "云端克隆中…" else "克隆到云端（MiniMax）")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BindingBadge(label: String, active: Boolean) {
    Text(
        label + (if (active) "·已绑定" else "·未绑定"),
        style = MaterialTheme.typography.labelSmall,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    )
}

/** Bottom sheet for quickly picking the current voice on the create page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(
    voices: List<VoiceProfileEntity>,
    currentId: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("选择当前音色", style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(voices, key = { it.id }) { voice ->
                    Surface(
                        onClick = { onPick(voice.id); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(voice.displayName) },
                            supportingContent = { Text(bindingSummary(voice)) },
                            trailingContent = {
                                if (voice.id == currentId) {
                                    Text("当前", color = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun bindingSummary(voice: VoiceProfileEntity): String = buildList {
    if (voice.hasLocalBinding) add("本地")
    if (voice.hasCloudBinding) add("云端")
    if (voice.hasSystemBinding) add("系统")
}.joinToString(" / ").ifEmpty { "尚未绑定生成方式" }