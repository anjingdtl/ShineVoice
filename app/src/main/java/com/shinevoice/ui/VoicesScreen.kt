package com.shinevoice.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.shinevoice.ShineVoiceApplication
import com.shinevoice.core.audio.TARGET_SAMPLE_RATE
import com.shinevoice.core.audio.VoiceRecorder
import com.shinevoice.core.storage.AudioPlaybackController
import com.shinevoice.data.db.VoiceProfileEntity
import com.shinevoice.ui.cyber.CyberButton
import com.shinevoice.ui.cyber.CyberCard
import com.shinevoice.ui.cyber.CyberChipState
import com.shinevoice.ui.cyber.CyberDialog
import com.shinevoice.ui.cyber.CyberOutlinedButton
import com.shinevoice.ui.cyber.CyberPageHeader
import com.shinevoice.ui.cyber.CyberStatusChip
import com.shinevoice.ui.cyber.CyberTextField
import com.shinevoice.ui.cyber.CyberType
import com.shinevoice.ui.cyber.LocalCyberColors
import com.shinevoice.ui.cyber.PulsingDot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音色页：声纹档案库 / Digital Voice Identity Library（UI Phase D）。
 *
 * Every VoiceProfile renders as a numbered digital identity dossier with
 * LOCAL / CLOUD / SYSTEM binding chips; the current profile carries the neon
 * highlight border. referenceText editing lives here, not on the create page.
 */
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
    val colors = LocalCyberColors.current
    var creating by remember { mutableStateOf(false) }
    var profileName by remember { mutableStateOf("") }
    var profileReferenceText by remember { mutableStateOf("") }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var recordingId by remember { mutableStateOf<String?>(null) }
    var pendingImportId by remember { mutableStateOf<String?>(null) }
    var pendingRecordId by remember { mutableStateOf<String?>(null) }
    var deletingProfile by remember { mutableStateOf<VoiceProfileEntity?>(null) }
    var renamingProfile by remember { mutableStateOf<VoiceProfileEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CyberPageHeader(title = "VOICE IDENTITY LIBRARY", code = "声纹档案库")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CyberButton(text = "＋ 创建音色", onClick = { creating = true }, modifier = Modifier.weight(1f))
                CyberOutlinedButton(
                    text = "切换当前音色 ▸",
                    onClick = {
                        val current = state.voices.firstOrNull { it.id == state.currentVoice?.id }
                        val next = state.voices.getOrNull(
                            (state.voices.indexOf(current) + 1).mod(state.voices.size),
                        )
                        next?.let { viewModel.setCurrentVoice(it.id) }
                    },
                    enabled = state.voices.size > 1,
                )
            }
        }
        if (state.voices.isEmpty()) {
            item {
                CyberCard {
                    Text("暂无音色档案，点击「创建音色」建立第一份声纹档案。", color = colors.textMuted, fontSize = 13.sp)
                }
            }
        } else {
            items(state.voices, key = { it.id }) { profile ->
                VoiceDossierCard(
                    index = state.voices.indexOf(profile) + 1,
                    profile = profile,
                    isCurrent = profile.id == state.currentVoice?.id,
                    expanded = profile.id == expandedId,
                    recording = profile.id == recordingId,
                    systemEngines = state.systemEngines,
                    cloudCloning = state.cloudCloning,
                    onExpand = { expandedId = if (expandedId == profile.id) null else profile.id },
                    onSetCurrent = { viewModel.setCurrentVoice(profile.id) },
                    onRequestRename = {
                        renamingProfile = profile
                        renameText = profile.displayName
                    },
                    onDelete = { deletingProfile = profile },
                    onPlayReference = {
                        val file = profile.referenceAudioPath?.let(::File)
                        if (file != null && file.isFile) {
                            playbackController.play(file)
                                .onFailure {
                                    Toast.makeText(context, "试听失败：${it.message}", Toast.LENGTH_LONG).show()
                                }
                        } else {
                            Toast.makeText(context, "该音色还没有参考音频。", Toast.LENGTH_SHORT).show()
                        }
                    },
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
                    onCloneToCloud = {
                        viewModel.cloneVoiceToCloud(profile.id) { ok, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    onBindSystemVoice = {
                        val engine = state.systemSelectedEngine
                            ?: state.systemEngines.firstOrNull { it.isSystemDefault }?.packageName
                        viewModel.bindProfileSystemVoice(profile.id, engine, state.systemSelectedVoice)
                        Toast.makeText(context, "已把当前系统语音绑定到该音色。", Toast.LENGTH_SHORT).show()
                    },
                    onClearSystemBinding = { viewModel.clearProfileSystemBinding(profile.id) },
                )
            }
        }
    }

    if (creating) {
        CyberDialog(
            onDismissRequest = { creating = false },
            title = "创建音色档案",
            code = "NEW VOICE PROFILE",
            actions = {
                CyberOutlinedButton(text = "取消", onClick = { creating = false })
                CyberButton(
                    text = "创建",
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
                )
            },
        ) {
            CyberTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = "音色名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            CyberTextField(
                value = profileReferenceText,
                onValueChange = { profileReferenceText = it },
                label = "参考文本（与录音内容一致，可稍后填写）",
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "创建后请录音或导入一段清晰的参考音频（推荐 5~15 秒），参考文本需与音频内容一致。",
                fontSize = 11.sp,
                color = colors.textMuted,
            )
        }
    }

    deletingProfile?.let { profile ->
        CyberDialog(
            onDismissRequest = { deletingProfile = null },
            title = "删除音色档案",
            code = "DELETE PROFILE",
            actions = {
                CyberOutlinedButton(text = "取消", onClick = { deletingProfile = null })
                CyberButton(
                    text = "删除",
                    onClick = {
                        viewModel.deleteVoice(profile.id)
                        deletingProfile = null
                    },
                )
            },
        ) {
            Text(
                "将删除「${profile.displayName}」及其参考音频，删除后无法恢复。确定删除吗？",
                fontSize = 13.sp,
                color = colors.textPrimary,
            )
        }
    }

    renamingProfile?.let { profile ->
        CyberDialog(
            onDismissRequest = { renamingProfile = null },
            title = "重命名音色",
            code = "RENAME PROFILE",
            actions = {
                CyberOutlinedButton(text = "取消", onClick = { renamingProfile = null })
                CyberButton(
                    text = "保存",
                    enabled = renameText.isNotBlank() && renameText != profile.displayName,
                    onClick = {
                        viewModel.renameVoice(profile.id, renameText.trim())
                        renamingProfile = null
                    },
                )
            },
        ) {
            CyberTextField(
                value = renameText,
                onValueChange = { renameText = it },
                label = "音色名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One numbered voice identity dossier. */
@Composable
private fun VoiceDossierCard(
    index: Int,
    profile: VoiceProfileEntity,
    isCurrent: Boolean,
    expanded: Boolean,
    recording: Boolean,
    systemEngines: List<com.shinevoice.provider.androidtts.SystemEngineInfo>,
    cloudCloning: Boolean,
    onExpand: () -> Unit,
    onSetCurrent: () -> Unit,
    onRequestRename: () -> Unit,
    onDelete: () -> Unit,
    onPlayReference: () -> Unit,
    onToggleRecord: () -> Unit,
    onImport: () -> Unit,
    onEditReferenceText: (String) -> Unit,
    onCloneToCloud: () -> Unit,
    onBindSystemVoice: () -> Unit,
    onClearSystemBinding: () -> Unit,
) {
    val colors = LocalCyberColors.current
    CyberCard(highlighted = isCurrent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "VOICE PROFILE // %03d".format(index),
                    style = CyberType.terminalLabel,
                    color = colors.cyan,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = colors.textPrimary,
                    )
                    if (isCurrent) {
                        Spacer(Modifier.padding(4.dp))
                        CyberStatusChip(text = "当前", state = CyberChipState.WARN)
                    }
                }
            }
            if (recording) {
                PulsingDot(colors.danger)
                Spacer(Modifier.padding(4.dp))
                Text("录音中", style = CyberType.terminalLabel, color = colors.danger)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CyberStatusChip(
                text = "LOCAL ${if (profile.hasLocalBinding) "READY" else "未就绪"}",
                state = if (profile.hasLocalBinding) CyberChipState.OK else CyberChipState.OFF,
            )
            CyberStatusChip(
                text = "CLOUD ${if (profile.hasCloudBinding) "LINKED" else "未绑定"}",
                state = if (profile.hasCloudBinding) CyberChipState.OK else CyberChipState.OFF,
            )
            CyberStatusChip(
                text = "SYSTEM ${if (profile.hasSystemBinding) "READY" else "未绑定"}",
                state = if (profile.hasSystemBinding) CyberChipState.OK else CyberChipState.OFF,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                profile.lastUsedAt.takeIf { it > 0 }
                    ?.let { "最后使用：${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it))}" }
                    ?: "尚未使用",
                style = CyberType.terminalLabel,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            CyberOutlinedButton(text = "▶ 试听", onClick = onPlayReference)
            Spacer(Modifier.padding(3.dp))
            CyberOutlinedButton(text = if (expanded) "收起 ▴" else "编辑 ▾", onClick = onExpand)
        }
        if (!isCurrent) {
            Spacer(Modifier.height(6.dp))
            CyberButton(text = "设为当前音色", onClick = onSetCurrent, modifier = Modifier.fillMaxWidth())
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(1.dp)) {}
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CyberOutlinedButton(
                    text = if (recording) "■ 停止录音" else "● 录音",
                    onClick = onToggleRecord,
                    tint = colors.danger,
                )
                CyberOutlinedButton(text = "导入音频", onClick = onImport)
                CyberOutlinedButton(text = "重命名", onClick = onRequestRename)
            }
            Spacer(Modifier.height(10.dp))
            Text("系统语音绑定", style = CyberType.terminalLabel, color = colors.textMuted)
            Text(
                if (profile.hasSystemBinding) {
                    val engineLabel = systemEngines.firstOrNull { it.packageName == profile.androidTtsEngine }?.label
                        ?: profile.androidTtsEngine.orEmpty()
                    "已绑定：$engineLabel · ${profile.androidTtsVoice ?: "默认语音"}"
                } else {
                    "未绑定系统语音"
                },
                fontSize = 12.sp,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CyberOutlinedButton(
                    text = "绑定当前系统语音",
                    onClick = onBindSystemVoice,
                    enabled = systemEngines.isNotEmpty(),
                )
                if (profile.hasSystemBinding) {
                    CyberOutlinedButton(text = "解除绑定", onClick = onClearSystemBinding)
                }
            }
            Spacer(Modifier.height(10.dp))
            CyberTextField(
                value = profile.referenceText ?: "",
                onValueChange = onEditReferenceText,
                label = "参考文本（与音频内容一致）",
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            profile.referenceAudioPath?.let { path ->
                val exists = File(path).isFile
                Text(
                    if (exists) "本地参考音频已就绪" else "本地参考音频缺失，请重新录音或导入",
                    fontSize = 12.sp,
                    color = if (exists) colors.success else colors.danger,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!profile.isDefault) {
                    CyberOutlinedButton(text = "删除音色", onClick = onDelete, tint = colors.danger)
                }
                if (profile.referenceAudioPath != null) {
                    CyberOutlinedButton(
                        text = if (cloudCloning) "云端克隆中……" else "克隆到云端（MiniMax）",
                        onClick = onCloneToCloud,
                        enabled = !cloudCloning,
                    )
                }
            }
        }
    }
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
    val colors = LocalCyberColors.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            CyberPageHeader(title = "SELECT VOICE", code = "选择当前音色")
            Spacer(Modifier.height(10.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(voices, key = { it.id }) { voice ->
                    CyberCard(
                        highlighted = voice.id == currentId,
                        onClick = { onPick(voice.id); onDismiss() },
                    ) {
                        Text(
                            voice.displayName,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Text(
                            bindingSummary(voice),
                            style = CyberType.terminalLabel,
                            color = colors.textMuted,
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
