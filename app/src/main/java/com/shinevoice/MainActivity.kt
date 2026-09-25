package com.shinevoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.shinevoice.ui.MainViewModel
import com.shinevoice.ui.ShineVoiceSplash
import com.shinevoice.ui.ShineVoiceRoot
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application as ShineVoiceApplication)
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
                ShineVoiceRoot(viewModel = appViewModel)
            }
        }
    }

    // Playback is owned by PlaybackService (foreground). Destroying the
    // Activity only unbinds this UI; audio and the overlay session keep
    // running so cross-app playback survives leaving the app.

    private companion object {
        const val SPLASH_DURATION_MS = 1_500L
    }
}
