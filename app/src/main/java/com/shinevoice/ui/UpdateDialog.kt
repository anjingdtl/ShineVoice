package com.shinevoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinevoice.ui.cyber.CyberButton
import com.shinevoice.ui.cyber.CyberChipState
import com.shinevoice.ui.cyber.CyberDialog
import com.shinevoice.ui.cyber.CyberKV
import com.shinevoice.ui.cyber.CyberOutlinedButton
import com.shinevoice.ui.cyber.CyberStatusChip
import com.shinevoice.ui.cyber.CyberType
import com.shinevoice.ui.cyber.LocalCyberColors
import com.shinevoice.ui.cyber.SweepScanLine
import com.shinevoice.update.UpdateUiState
import com.shinevoice.update.UpdateProtocol

/** Cyber UI overlay for update discovery, download progress and installation hand-off. */
@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onResumeInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCyberColors.current
    val candidate = when (state) {
        is UpdateUiState.Available -> state.candidate
        is UpdateUiState.Downloading -> state.candidate
        is UpdateUiState.AwaitingInstall -> state.candidate
        else -> null
    }
    val force = candidate?.let {
        UpdateProtocol.requiresImmediateUpdate(com.shinevoice.BuildConfig.VERSION_CODE.toLong(), it)
    } == true
    val dismiss = if (
        force ||
        state is UpdateUiState.Downloading ||
        state is UpdateUiState.Installing
    ) {
        {}
    } else {
        onDismiss
    }
    CyberDialog(
        onDismissRequest = dismiss,
        title = when (state) {
            is UpdateUiState.Available -> "发现新版本"
            is UpdateUiState.Downloading -> "正在下载更新包"
            is UpdateUiState.AwaitingInstall -> "准备安装更新"
            UpdateUiState.Installing -> "正在打开系统安装器"
            is UpdateUiState.Failed -> "更新失败"
            else -> "软件更新"
        },
        code = "SOFTWARE UPDATE",
        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
        actions = {
            when (state) {
                is UpdateUiState.Available -> {
                    if (!force) CyberOutlinedButton(text = "稍后", onClick = onDismiss)
                    CyberButton(text = "立即更新", onClick = onDownload)
                }
                is UpdateUiState.AwaitingInstall -> {
                    CyberButton(text = "继续安装", onClick = onResumeInstall)
                    if (!force) CyberOutlinedButton(text = "稍后", onClick = onDismiss)
                }
                is UpdateUiState.Downloading -> Unit
                is UpdateUiState.Failed -> CyberOutlinedButton(text = "关闭", onClick = onDismiss)
                is UpdateUiState.Installing -> Unit
                else -> CyberOutlinedButton(text = "关闭", onClick = onDismiss)
            }
        },
    ) {
        when (state) {
            is UpdateUiState.Available -> {
                candidate?.let { UpdateCandidateSummary(it) }
            }
            is UpdateUiState.Downloading -> {
                val percent = if (state.totalBytes > 0) {
                    (state.downloadedBytes * 100 / state.totalBytes).coerceIn(0, 100)
                } else {
                    0
                }
                CyberStatusChip("下载中 $percent%", state = CyberChipState.INFO, pulse = true)
                Spacer(Modifier.height(10.dp))
                SweepScanLine()
                Spacer(Modifier.height(8.dp))
                CyberKV("PROGRESS", "${formatUpdateBytes(state.downloadedBytes)} / ${formatUpdateBytes(state.totalBytes)}")
            }
            is UpdateUiState.AwaitingInstall -> {
                CyberStatusChip(
                    text = if (state.permissionRequired) "等待安装权限" else "等待确认",
                    state = CyberChipState.WARN,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.permissionRequired) {
                        "请在系统设置中允许 ShineVoice 安装未知来源应用，返回后点击“继续安装”。"
                    } else {
                        "安装包已完成完整性、包名、版本和签名校验。"
                    },
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                CyberKV("VERSION", "V${state.candidate.metadata.versionName}")
            }
            UpdateUiState.Installing -> {
                CyberStatusChip("已交给系统安装器", state = CyberChipState.OK, pulse = true)
                Spacer(Modifier.height(8.dp))
                Text("最终安装确认由 Android 系统完成。", fontSize = 13.sp, color = colors.textPrimary)
            }
            is UpdateUiState.Failed -> {
                CyberStatusChip("已拒绝安装", state = CyberChipState.ERROR)
                Spacer(Modifier.height(8.dp))
                Text(state.message, fontSize = 13.sp, color = colors.danger)
            }
            else -> Text("正在读取公开 Release 信息……", fontSize = 13.sp, color = colors.textMuted)
        }
    }
}

@Composable
private fun UpdateCandidateSummary(candidate: com.shinevoice.update.UpdateCandidate) {
    val colors = LocalCyberColors.current
    CyberStatusChip("可用更新", state = CyberChipState.OK, pulse = true)
    Spacer(Modifier.height(8.dp))
    CyberKV("CURRENT", "V${com.shinevoice.BuildConfig.VERSION_NAME}")
    CyberKV("LATEST", "V${candidate.metadata.versionName}", valueColor = colors.accent)
    CyberKV("APK SIZE", formatUpdateBytes(candidate.metadata.apkSizeBytes))
    Spacer(Modifier.height(8.dp))
    Text(candidate.metadata.title, fontWeight = FontWeight.Bold, color = colors.textPrimary)
    if (candidate.metadata.notes.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            candidate.metadata.notes.forEach { note ->
                Text("• $note", fontSize = 12.sp, color = colors.textMuted)
            }
        }
    }
}

private fun formatUpdateBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
