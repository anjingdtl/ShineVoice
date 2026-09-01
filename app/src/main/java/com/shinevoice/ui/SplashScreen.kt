package com.shinevoice.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.shinevoice.R

/**
 * Brand-first launch surface for the v1.0 release. The generated Vocal Core
 * artwork owns the typography and signal geometry, so the runtime only needs
 * to provide a full-bleed, edge-to-edge canvas while the app initializes.
 */
@Composable
fun ShineVoiceSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070C)),
    ) {
        Image(
            painter = painterResource(R.drawable.splash_vocal_core),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
