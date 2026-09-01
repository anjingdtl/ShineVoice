package com.shinevoice.ui.cyber

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.navigationBarsPadding

/** Chip semantics: green READY, cyan info, yellow warn, gray off, red error. */
enum class CyberChipState(val tintOf: (CyberColors) -> Color) {
    OK({ it.success }),
    INFO({ it.cyan }),
    WARN({ it.accent }),
    OFF({ it.textMuted }),
    ERROR({ it.danger }),
}

/**
 * App-wide cyberpunk background: solid deep base plus a faint HUD grid.
 * The grid is a cheap static drawBehind — no per-frame cost.
 */
@Composable
fun CyberBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalCyberColors.current
    Box(
        modifier = modifier.drawBehind {
            drawRect(colors.background)
            val step = 30.dp.toPx()
            val line = colors.gridLine
            var x = 0f
            while (x < size.width) {
                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(line, Offset(0f, y), Offset(size.width, y), 1f)
                y += step
            }
        },
    ) {
        content()
    }
}

/** Press feedback is built into CyberButton/CyberOutlinedButton via scale. */

/**
 * Core HUD panel: cut-corner surface, hairline border, L-shaped corner ticks
 * at top-start/bottom-end; optional neon glow border when [highlighted]
 * (current voice, active selection). Cheap layered strokes, no blur.
 */
@Composable
fun CyberCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    minTickLen: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalCyberColors.current
    val tick = if (highlighted) colors.accent else colors.outlineStrong
    val borderColor = if (highlighted) colors.accent else colors.outline
    val shape = CyberShape.card
    val cardModifier = Modifier
        .fillMaxWidth()
        .then(
            if (highlighted) {
                Modifier
                    .border(5.dp, colors.glow, shape)
                    .border(2.dp, colors.accent.copy(alpha = 0.45f), shape)
            } else {
                Modifier
            },
        )
        .drawBehind {
            val len = minTickLen.toPx()
            val w = 2.dp.toPx()
            // top-start L tick
            drawLine(tick, Offset(0f, 0f), Offset(len, 0f), w)
            drawLine(tick, Offset(0f, 0f), Offset(0f, len), w)
            // bottom-end L tick
            drawLine(tick, Offset(size.width - len, size.height), Offset(size.width, size.height), w)
            drawLine(tick, Offset(size.width, size.height - len), Offset(size.width, size.height), w)
        }
    Box(modifier) {
        if (onClick != null) {
            Surface(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                color = colors.surface,
                border = BorderStroke(1.dp, borderColor),
                modifier = cardModifier,
            ) {
                Column(Modifier.padding(contentPadding), content = content)
            }
        } else {
            Surface(
                shape = shape,
                color = colors.surface,
                border = BorderStroke(1.dp, borderColor),
                modifier = cardModifier,
            ) {
                Column(Modifier.padding(contentPadding), content = content)
            }
        }
    }
}

/**
 * Numbered section header in terminal style: `01 // MODEL & SERVICE` code in
 * monospace plus the user-facing Chinese title, closed by a HUD divider.
 */
@Composable
fun CyberSectionHeader(
    index: String,
    code: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCyberColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$index", style = CyberType.sectionCode, color = colors.accent)
            Spacer(Modifier.width(6.dp))
            Text("// $code", style = CyberType.sectionCode, color = colors.cyan)
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = colors.textPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .drawBehind {
                    drawRect(colors.outline)
                    drawRect(colors.cyan.copy(alpha = 0.65f), size = androidx.compose.ui.geometry.Size(size.width * 0.28f, size.height))
                },
        )
    }
}

/** Page-level terminal masthead, e.g. SHINEVOICE / VOICE SYNTHESIS TERMINAL. */
@Composable
fun CyberPageHeader(
    title: String,
    code: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCyberColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = CyberType.sectionCode.copy(fontSize = 16.sp),
                color = colors.accent,
            )
            Text(code, style = CyberType.terminalLabel, color = colors.cyan)
        }
        trailing?.invoke()
    }
}

/** Status chip: terminal label + optional pulsing status dot. */
@Composable
fun CyberStatusChip(
    text: String,
    state: CyberChipState = CyberChipState.INFO,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalCyberColors.current
    val tint = state.tintOf(colors)
    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .border(1.dp, tint.copy(alpha = 0.75f), CyberShape.chip)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (pulse) PulsingDot(tint) else Box(Modifier.size(6.dp).drawBehind { drawRect(tint) })
            Text(
                text,
                style = CyberType.terminalLabel,
                color = if (state == CyberChipState.OFF) colors.textMuted else tint,
            )
        }
    }
}

/** Breathing status dot — the only always-on animation, very cheap (alpha). */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, diameter: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(
        modifier = modifier
            .size(diameter)
            .drawBehind { drawRect(color.copy(alpha = alpha)) },
    )
}

/** Primary action button: filled neon yellow, cut corners, press scale. */
@Composable
fun CyberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = true,
) {
    val colors = LocalCyberColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = if (pressed) 0.97f else 1f
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CyberShape.button,
        color = if (prominent) colors.accent else colors.surfaceHigh,
        border = BorderStroke(
            1.dp,
            when {
                prominent -> colors.accent
                enabled -> colors.outlineStrong
                else -> colors.outline
            },
        ),
        interactionSource = interaction,
        modifier = modifier.scale(scale),
    ) {
        Text(
            text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = when {
                prominent -> colors.onAccent
                enabled -> colors.textPrimary
                else -> colors.textMuted
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Secondary/tertiary button: outlined, cyan-tinted label. */
@Composable
fun CyberOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val colors = LocalCyberColors.current
    val effective = tint ?: colors.cyan
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CyberShape.button,
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (enabled) effective.copy(alpha = 0.8f) else colors.outline),
        interactionSource = interaction,
        modifier = modifier.scale(if (pressed) 0.97f else 1f),
    ) {
        Text(
            text,
            style = CyberType.terminalValue,
            color = if (enabled) effective else colors.textMuted,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Terminal text field: cut-corner outline, cyan caret, HUD label. */
@Composable
fun CyberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    password: Boolean = false,
) {
    val colors = LocalCyberColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        label = {
            Text(label, style = CyberType.terminalLabel, color = colors.textMuted)
        },
        visualTransformation = if (password) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = if (password) {
            androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            )
        } else {
            androidx.compose.foundation.text.KeyboardOptions.Default
        },
        shape = CyberShape.cardSmall,
        textStyle = TextStyle(fontSize = 14.sp, color = colors.textPrimary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.cyan,
            unfocusedBorderColor = colors.outline,
            disabledBorderColor = colors.outline,
            focusedLabelColor = colors.cyan,
            cursorColor = colors.accent,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
        ),
    )
}

/** Selectable terminal chip (generation mode, region, theme...). */
@Composable
fun CyberFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCyberColors.current
    val border = if (selected) colors.accent else colors.outline
    Box(
        modifier = modifier
            .then(
                Modifier
                    .clickable(onClick = onClick)
                    .then(
                        if (selected) {
                            Modifier
                                .border(3.dp, colors.glow, CyberShape.chip)
                                .border(1.dp, border, CyberShape.chip)
                        } else {
                            Modifier.border(1.dp, border, CyberShape.chip)
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ),
    ) {
        Text(
            label,
            style = CyberType.terminalValue,
            color = if (selected) colors.accent else colors.textMuted,
        )
    }
}

/** Slider with neon thumb and cyan track. */
@Composable
fun CyberSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalCyberColors.current
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.cyan,
            inactiveTrackColor = colors.outline,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}

/** Scanning progress bar: sweeping neon gradient, only animate while active. */
@Composable
fun SweepScanLine(
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    active: Boolean = true,
) {
    val colors = LocalCyberColors.current
    val transition = rememberInfiniteTransition(label = "sweep")
    val progress by transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweepX",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawRect(colors.outline)
                if (active) {
                    val w = size.width * 0.45f
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                colors.cyan.copy(alpha = 0.3f),
                                colors.accent,
                                colors.cyan.copy(alpha = 0.3f),
                                Color.Transparent,
                            ),
                        ),
                        topLeft = Offset(size.width * progress, 0f),
                        size = androidx.compose.ui.geometry.Size(w, size.height),
                    )
                }
            },
    )
}

/** Bottom navigation in terminal style: mono glyph + label + active top bar. */
data class CyberNavItem(val code: String, val glyph: String, val label: String)

@Composable
fun CyberNavigationBar(
    items: List<CyberNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCyberColors.current
    Column(modifier = modifier.fillMaxWidth().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(colors.outlineStrong) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(index) }
                        .padding(top = 6.dp, bottom = 8.dp)
                        .widthIn(min = 72.dp),
                ) {
                    Box(
                        Modifier
                            .width(30.dp)
                            .height(2.dp)
                            .drawBehind { if (selected) drawRect(colors.accent) },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.glyph,
                        style = CyberType.terminalValue,
                        color = if (selected) colors.accent else colors.textMuted,
                    )
                    Text(
                        item.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) colors.textPrimary else colors.textMuted,
                    )
                }
            }
        }
    }
}

/** Fully custom dialog: cut-corner bordered panel over a dim scrim. */
@Composable
fun CyberDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    code: String = "SYSTEM DIALOG",
    content: @Composable ColumnScope.() -> Unit = {},
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCyberColors.current
    Dialog(onDismissRequest = onDismissRequest) {
        CyberCard(modifier = modifier, contentPadding = PaddingValues(16.dp)) {
            Text(code, style = CyberType.terminalLabel, color = colors.cyan)
            Spacer(Modifier.height(4.dp))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.textPrimary)
            Spacer(Modifier.height(10.dp))
            content()
            actions?.let {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() }
            }
        }
    }
}

/** Simple monospace key-value line used across diagnostics and archives. */
@Composable
fun CyberKV(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = LocalCyberColors.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(key, style = CyberType.terminalLabel, color = colors.textMuted)
        Text(
            value,
            style = CyberType.terminalValue.copy(fontSize = 12.sp),
            color = valueColor ?: colors.textPrimary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
