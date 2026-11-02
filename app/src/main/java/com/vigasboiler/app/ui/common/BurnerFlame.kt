package com.vigasboiler.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vigasboiler.app.R
import com.vigasboiler.app.ui.theme.AppColors

/**
 * Burner state at a glance: a flickering flame with a glowing halo inside a modulation ring
 * while the burner runs, a grey still flame when it is off. Turning on "ignites" with a pop.
 * The infinite animations only exist while the burner is on.
 */
@Composable
fun BurnerFlame(on: Boolean, modulationPercent: Double?, size: Dp = 56.dp) {
    val hot = AppColors.extra.hot
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    // Modulation ring glides to new values; empty when off.
    val ring by animateFloatAsState(
        if (on) ((modulationPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) else 0f,
        spring(stiffness = Spring.StiffnessLow),
        label = "modulation",
    )
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.08f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (ring > 0f) drawArc(hot, -90f, 360f * ring, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        AnimatedContent(
            targetState = on,
            transitionSpec = {
                if (targetState) {
                    (scaleIn(spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.3f) + fadeIn()) togetherWith fadeOut(tween(150))
                } else {
                    fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                }
            },
            label = "burner",
        ) { isOn ->
            if (isOn) LiveFlame(size) else Icon(
                painterResource(R.drawable.ic_nav_heating), null,
                Modifier.size(size * 0.45f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun LiveFlame(size: Dp) {
    val hot = AppColors.extra.hot
    val t = rememberInfiniteTransition(label = "flame")
    // Different periods so the flicker never looks mechanical.
    val scale by t.animateFloat(0.90f, 1.10f, infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
    val sway by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(620, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway")
    val glow by t.animateFloat(0.25f, 0.55f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.78f)) {
            drawCircle(Brush.radialGradient(listOf(hot.copy(alpha = glow), hot.copy(alpha = 0f))))
        }
        Icon(
            painterResource(R.drawable.ic_nav_heating), null,
            Modifier.size(size * 0.48f).graphicsLayer {
                scaleX = 0.96f + (scale - 1f) * 0.5f
                scaleY = scale
                rotationZ = sway
                transformOrigin = TransformOrigin(0.5f, 1f) // flicker from the base
            },
            tint = hot,
        )
    }
}
