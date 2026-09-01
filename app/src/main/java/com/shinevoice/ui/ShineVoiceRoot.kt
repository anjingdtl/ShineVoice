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
