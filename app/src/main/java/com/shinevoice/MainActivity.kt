package com.shinevoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.shinevoice.core.audio.AudioRouteManager
import com.shinevoice.core.storage.AudioPlaybackController
import com.shinevoice.ui.MainViewModel
import com.shinevoice.ui.ShineVoiceRoot

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
            ShineVoiceRoot(
                viewModel = viewModel,
                playbackController = playbackController,
            )
        }
    }

    override fun onDestroy() {
        playbackController.release()
        super.onDestroy()
    }
}
