package com.shinevoice.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinevoice.core.audio.PlaybackRoute
import com.shinevoice.domain.tts.TtsResult
import com.shinevoice.provider.androidtts.AndroidSystemTtsProvider
import com.shinevoice.provider.minimax.MiniMaxProvider
import com.shinevoice.provider.sherpa.SherpaZipVoiceProvider
import com.shinevoice.ui.cyber.CyberButton
import com.shinevoice.ui.cyber.CyberCard
import com.shinevoice.ui.cyber.CyberChipState
import com.shinevoice.ui.cyber.CyberFilterChip
import com.shinevoice.ui.cyber.CyberOutlinedButton
import com.shinevoice.ui.cyber.CyberPageHeader
import com.shinevoice.ui.cyber.CyberSlider
import com.shinevoice.ui.cyber.CyberStatusChip
import com.shinevoice.ui.cyber.CyberTextField
import com.shinevoice.ui.cyber.CyberType
import com.shinevoice.ui.cyber.LocalCyberColors
import com.shinevoice.ui.cyber.PulsingDot
import com.shinevoice.ui.cyber.SweepScanLine
import com.shinevoice.ui.cyber.formatDurationClock
import java.util.Locale

/**
 * 创作页：AI 语音生成控制终端（UI Phase C）。
 *
 * Normal users see only voice / mode / text / speed / generate / result —
 * engineering metrics (ms timings, provider ids, RTF...) stay in
 * 设置 → 高级 → 开发与诊断.
 */
@Composable
fun CreateScreen(
    state: MainUiState,
    padding: PaddingValues,
    onTargetTextChanged: (String) -> Unit,
    onSpeedChanged: (Float) -> Unit,
    onCurrentVoiceClick: () -> Unit,
    onSelectProvider: (String) -> Unit,
    providerLabel: (String) -> String,
    onGenerate: () -> Unit,
    onPlaybackRouteChanged: (PlaybackRoute) -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalCyberColors.current
    val providerOptions = listOf(
        SherpaZipVoiceProvider.PROVIDER_ID,
        MiniMaxProvider.PROVIDER_ID,
        AndroidSystemTtsProvider.PROVIDER_ID,
    )
    val isLocalMode = state.selectedProviderId == SherpaZipVoiceProvider.PROVIDER_ID
    val isCloudMode = state.selectedProviderId == MiniMaxProvider.PROVIDER_ID
    val referenceReady = state.referenceStatus?.ready == true

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CyberPageHeader(title = "SHINEVOICE", code = "VOICE SYNTHESIS TERMINAL")
        }

        // 当前音色
        item {
            CyberCard(onClick = onCurrentVoiceClick, highlighted = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前音色", style = CyberType.terminalLabel, color = colors.textMuted)
                        Text(
                            state.currentVoice?.displayName ?: "默认参考音色",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = colors.textPrimary,
                        )
                    }
                    val chip = when {
                        !isLocalMode || referenceReady -> CyberChipState.OK
                        else -> CyberChipState.WARN
                    }
                    CyberStatusChip(
                        text = if (!isLocalMode || referenceReady) "READY" else "未就绪",
                        state = chip,
                        pulse = !isLocalMode || referenceReady,
                    )
                }
                if (isLocalMode && !referenceReady) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.referenceStatus?.summary
                            ?: "该音色还没有可用于本地生成的参考音频，请到音色库录音或导入。",
                        fontSize = 12.sp,
                        color = colors.accent,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("点击切换音色 ▸", style = CyberType.terminalLabel, color = colors.cyan)
            }
        }

        // 生成方式
        item {
            CyberCard {
                Text("生成方式", style = CyberType.terminalLabel, color = colors.textMuted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    providerOptions.forEach { providerId ->
                        CyberFilterChip(
                            selected = state.selectedProviderId == providerId,
                            label = providerLabel(providerId),
                            onClick = { onSelectProvider(providerId) },
                        )
                    }
                }
                if (isCloudMode && state.currentVoice?.minimaxVoiceId == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前音色还没有云端音色，请到音色库克隆。",
                        fontSize = 12.sp,
                        color = colors.accent,
                    )
                }
            }
        }

        // 文本输入
        item {
            Column {
                Text("TEXT INPUT", style = CyberType.sectionCode, color = colors.cyan)
                Spacer(Modifier.height(6.dp))
                CyberTextField(
                    value = state.targetText,
                    onValueChange = onTargetTextChanged,
                    label = "输入需要生成的文字……",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    enabled = !state.isGenerating && !state.stabilityRunning,
                )
            }
        }

        // 语速
        item {
            CyberCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语速", style = CyberType.terminalLabel, color = colors.textMuted)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "SPEED ${"%.2f".format(Locale.US, state.speed)}x",
                        style = CyberType.terminalValue,
                        color = colors.accent,
                    )
                }
                CyberSlider(
                    value = state.speed,
                    onValueChange = onSpeedChanged,
                    valueRange = 0.5f..2.0f,
                    enabled = !state.isGenerating && !state.stabilityRunning,
                )
            }
        }

        // 生成按钮 + 扫描动效
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isGenerating || state.stabilityRunning) {
                    SweepScanLine()
                }
                CyberButton(
                    text = when {
                        state.isGenerating -> "生成中……"
                        state.stabilityRunning -> "测试中 ${state.stabilityCompleted}/20"
                        isCloudMode && state.currentVoice?.minimaxVoiceId == null -> "先克隆云端音色"
                        else -> "生成语音"
                    },
                    onClick = onGenerate,
                    enabled = !state.isGenerating && !state.stabilityRunning && !(
                        isCloudMode &&
                            (state.minimaxStatus == "未配置" || state.currentVoice?.minimaxVoiceId == null)
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.message?.let { message ->
                    Text(
                        message,
                        fontSize = 12.sp,
                        color = if (state.lastResult?.success == false) colors.danger else colors.cyan,
                    )
                }
            }
        }

        // 生成结果
        state.lastResult?.let { result ->
            item {
                GenerationResultCard(
                    result = result,
                    providerLabel = providerLabel(result.providerId),
                    playbackRoute = state.playbackRoute,
                    onPlaybackRouteChanged = onPlaybackRouteChanged,
                    onPlay = onPlay,
                    onSave = onSave,
                    onShare = onShare,
                )
            }
        }

        item {
            Text(
                "请仅使用本人声音或已获得明确授权的声音素材。本地生成不联网；云端生成会把文本与参考音频上传到所选云端服务。",
                fontSize = 11.sp,
                color = colors.textMuted,
            )
        }
    }
}

/** 结果卡只展示用户语言：成功/失败、时长、播放/保存/分享、播放输出切换。 */
@Composable
fun GenerationResultCard(
    result: TtsResult,
    providerLabel: String,
    playbackRoute: PlaybackRoute,
    onPlaybackRouteChanged: (PlaybackRoute) -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = LocalCyberColors.current
    CyberCard(highlighted = result.success) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CyberStatusChip(
                text = if (result.success) "生成成功" else "生成失败",
                state = if (result.success) CyberChipState.OK else CyberChipState.ERROR,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "生成方式：$providerLabel",
                style = CyberType.terminalLabel,
                color = colors.textMuted,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (result.success) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("▶", style = CyberType.terminalValue.copy(fontSize = 22.sp), color = colors.accent)
                Spacer(Modifier.padding(3.dp))
                Text(
                    result.durationMs?.let { formatDurationClock(it) } ?: "--:--",
                    style = CyberType.terminalValue.copy(fontSize = 26.sp),
                    color = colors.textPrimary,
                )
                Spacer(Modifier.padding(4.dp))
                PulsingDot(colors.cyan, diameter = 6.dp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CyberButton(text = "播放", onClick = onPlay)
                CyberOutlinedButton(text = "保存", onClick = onSave)
                CyberOutlinedButton(text = "分享", onClick = onShare, tint = colors.magenta)
            }
            Spacer(Modifier.height(10.dp))
            CyberOutlinedButton(
                text = "播放输出：${playbackRoute.displayName} · 切换听筒/外放",
                onClick = {
                    onPlaybackRouteChanged(
                        if (playbackRoute == PlaybackRoute.EARPIECE) PlaybackRoute.SPEAKER
                        else PlaybackRoute.EARPIECE,
                    )
                },
            )
        } else {
            Text(
                result.error?.userMessage ?: "生成失败，请重试。",
                fontSize = 13.sp,
                color = colors.danger,
            )
        }
    }
}
