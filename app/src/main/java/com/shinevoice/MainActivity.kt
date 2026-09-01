package com.shinevoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.shinevoice.core.audio.AudioRouteManager
import com.shinevoice.core.storage.AudioPlaybackController
import com.shinevoice.ui.MainViewModel
import com.shinevoice.ui.ShineVoiceSplash
import com.shinevoice.ui.ShineVoiceRoot
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application as ShineVoiceApplication)
    }
    private val playbackController by lazy {
        AudioPlaybackController(AudioRouteManager(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Start data/model initialization while the brand splash is visible.
            val appViewModel = viewModel
            var showSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(SPLASH_DURATION_MS)
                showSplash = false
            }

            if (showSplash) {
                ShineVoiceSplash()
            } else {
                ShineVoiceRoot(
                    viewModel = appViewModel,
                    playbackController = playbackController,
                )
            }
        }
    }

    override fun onDestroy() {
        playbackController.release()
        super.onDestroy()
    }

    private companion object {
        const val SPLASH_DURATION_MS = 1_500L
    }
}
