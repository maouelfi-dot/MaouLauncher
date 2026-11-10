/*
 * Maou Launcher
 * Based on Zalith Launcher 2, Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 * Licensed under the GNU General Public License v3 or later.
 */

package com.movtery.zalithlauncher.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * [Maou] Flat, very cheap replacement for the wavy progress bar.
 * The animation value is only read in the draw phase, so nothing recomposes while it runs.
 * Pass [progress] for a determinate bar; leave it null for the sliding "indeterminate" bar.
 */
@Composable
fun MaouProgressBar(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
) {
    val phase = if (progress == null) {
        rememberInfiniteTransition(label = "maouProgress").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
            label = "maouProgressPhase"
        )
    } else null

    Spacer(
        modifier = modifier
            .height(4.dp)
            .clipToBounds()
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(trackColor, size = size, cornerRadius = r)
                if (progress != null) {
                    val w = size.width * progress().coerceIn(0f, 1f)
                    if (w > 0f) drawRoundRect(color, size = Size(w, size.height), cornerRadius = r)
                } else {
                    val seg = size.width * 0.35f
                    val x = -seg + (size.width + seg) * (phase?.value ?: 0f)
                    drawRoundRect(color, topLeft = Offset(x, 0f), size = Size(seg, size.height), cornerRadius = r)
                }
            }
    )
}
