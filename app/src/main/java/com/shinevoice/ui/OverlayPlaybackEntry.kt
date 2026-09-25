package com.shinevoice.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinevoice.feature.overlay.OverlayControllerService
import com.shinevoice.ui.cyber.CyberButton
import com.shinevoice.ui.cyber.CyberDialog
import com.shinevoice.ui.cyber.CyberOutlinedButton
import com.shinevoice.ui.cyber.LocalCyberColors

/**
 * 悬浮播放入口：权限引导 + 会话开关。
 *
 * Flow per the product design: generate & pick a clip first → enable the
 * floating button → switch to WeChat → tap 倒计时 → hold the talk button →
 * the clip plays on the speaker when the countdown fires.
 */
@Composable
fun OverlayPlaybackEntry(compact: Boolean = false) {
    val context = LocalContext.current
    val colors = LocalCyberColors.current
    var explain by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(OverlayControllerService.isRunning) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        // Re-check on return from the system overlay-permission screen.
        if (Settings.canDrawOverlays(context)) {
            enabled = true
            OverlayControllerService.start(context)
        } else {
            android.widget.Toast.makeText(
                context,
                "未授予悬浮窗权限，悬浮播放不可用。",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun requestPermissionThenStart() {
        if (Settings.canDrawOverlays(context)) {
            enabled = true
            OverlayControllerService.start(context)
        } else {
            explain = true
        }
    }

    if (enabled) {
        CyberOutlinedButton(
            text = if (compact) "悬浮●" else "关闭悬浮播放",
            onClick = {
                enabled = false
                OverlayControllerService.stop(context)
            },
        )
    } else {
        CyberOutlinedButton(
            text = if (compact) "悬浮○" else "开启悬浮播放",
            onClick = { requestPermissionThenStart() },
        )
    }

    if (explain) {
        CyberDialog(
            onDismissRequest = { explain = false },
            title = "开启悬浮播放",
            code = "OVERLAY PERMISSION",
            actions = {
                CyberOutlinedButton(text = "取消", onClick = { explain = false })
                CyberButton(
                    text = "去授权",
                    onClick = {
                        explain = false
                        permissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    },
                )
            },
        ) {
            Text(
                "悬浮播放会在其他应用上方显示一个可拖动的小圆钮，用于微信等场景的外放辅助：",
                fontSize = 12.sp,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "1. 在本应用生成并试听正常的语音；\n" +
                    "2. 开启悬浮，切到微信；\n" +
                    "3. 点圆钮选择音频并倒计时（默认 3 秒）；\n" +
                    "4. 倒计时内按住微信“按住说话”，到时语音从扬声器外放，由微信麦克风收录。",
                fontSize = 12.sp,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "需要“显示在其他应用上层”权限。普通应用无法直接向微信麦克风注入音频，" +
                    "实际收录效果取决于设备与微信版本，请以真机实测为准。",
                fontSize = 11.sp,
                color = colors.textMuted,
            )
        }
    }
}

/** True while the overlay session service is alive (same-process check). */
@Suppress("unused")
private fun overlayRunning(): Boolean = OverlayControllerService.isRunning
