package com.vigasboiler.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class TabIconKind { Overview, Heating, Stats, Messages, More }

/**
 * Bottom-bar icon that plays a short animation when its tab gets selected: tiles pop in,
 * the flame flares up, bars grow, the bell rings, the menu lines slide in. Selected icons are
 * solid in both states, the colour (accent or neutral) comes from [color]. Drawn on a 24-unit grid
 * like the vector icons.
 */
@Composable
fun TabIcon(kind: TabIconKind, selected: Boolean, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    // 1 = resting; the animation runs 0 → 1 each time the tab becomes selected.
    val progress = remember { Animatable(1f) }
    val bell = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (!selected) return@LaunchedEffect
        if (kind == TabIconKind.Messages) {
            bell.animateTo(0f, keyframes { durationMillis = 700; 18f at 90; -14f at 210; 9f at 340; -5f at 460; 2f at 580 })
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
        }
    }
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        when (kind) {
            TabIconKind.Overview -> overview(u, progress.value, selected, color)
            TabIconKind.Heating -> heating(u, progress.value, selected, color)
            TabIconKind.Stats -> stats(u, progress.value, selected, color)
            TabIconKind.Messages -> rotate(bell.value, pivot = Offset(12f * u, 3f * u)) { messages(u, color) }
            TabIconKind.More -> more(u, progress.value, selected, color)
        }
    }
}

private val FLAME = PathParser().parsePathString("M12,2C12,2 6,9 6,14a6,6 0,0 0,12 0C18,10 15,7 14,5C13.5,8 12.5,9 11,9.5C12,7 12,4 12,2z").toPath()
private val FLAME_CORE = PathParser().parsePathString("M12,11C12,11 9.2,14.2 9.2,16.3a2.8,2.8 0,0 0,5.6 0C14.8,14.6 13.2,13 12,11z").toPath()
private val BELL = PathParser().parsePathString(
    "M12,22a2,2 0,0 0,2 -2h-4a2,2 0,0 0,2 2zM18,16v-5c0,-3.1 -1.6,-5.6 -4.5,-6.3V4a1.5,1.5 0,0 0,-3 0v0.7C7.6,5.4 6,7.9 6,11v5l-2,2v1h16v-1l-2,-2z",
).toPath()

/** Four tiles that pop in one after another. */
private fun DrawScope.overview(u: Float, p: Float, selected: Boolean, color: Color) {
    val tiles = listOf(floatArrayOf(3f, 3f, 8f, 10f), floatArrayOf(13f, 3f, 8f, 6f), floatArrayOf(3f, 15f, 8f, 6f), floatArrayOf(13f, 11f, 8f, 10f))
    tiles.forEachIndexed { i, (x, y, w, h) ->
        val local = ((p - i * 0.12f) / (1f - 0.36f)).coerceIn(0f, 1.2f)
        val s = 0.35f + 0.65f * local
        scale(s, pivot = Offset((x + w / 2) * u, (y + h / 2) * u)) {
            drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(2f * u))
        }
    }
}

/** The flame flares up from its base and settles; the bright core appears when selected. */
private fun DrawScope.heating(u: Float, p: Float, selected: Boolean, color: Color) {
    withTransform({
        scale(u, u, pivot = Offset.Zero)
        scale(0.9f + 0.1f * p, 0.55f + 0.45f * p, pivot = Offset(12f, 21f))
        rotate((1f - p) * -10f, pivot = Offset(12f, 21f))
    }) {
        drawPath(FLAME, color)
        if (selected) drawPath(FLAME_CORE, Color.White.copy(alpha = 0.55f * p))
    }
}

/** Three bars that grow from the baseline, staggered. */
private fun DrawScope.stats(u: Float, p: Float, selected: Boolean, color: Color) {
    drawRoundRect(color, Offset(4f * u, 18.5f * u), Size(16f * u, 1.6f * u), CornerRadius(0.8f * u))
    val bars = listOf(6f to 8f, 10.5f to 4f, 15f to 10f) // x, top
    bars.forEachIndexed { i, (x, top) ->
        val local = ((p - i * 0.15f) / 0.7f).coerceIn(0f, 1.15f)
        val height = (16f - top) * local
        val y = 16.5f - height
        drawRoundRect(color, Offset(x * u, y * u), Size(3f * u, height * u), CornerRadius(1f * u))
    }
}

private fun DrawScope.messages(u: Float, color: Color) {
    withTransform({ scale(u, u, pivot = Offset.Zero) }) {
        drawPath(BELL, color)
    }
}

/** Menu lines that slide in from the left, the middle one shorter. */
private fun DrawScope.more(u: Float, p: Float, selected: Boolean, color: Color) {
    val lines = listOf(6f to 18f, 11f to 13f, 16f to 18f)
    lines.forEachIndexed { i, (y, width) ->
        val local = ((p - i * 0.12f) / 0.76f).coerceIn(0f, 1.1f)
        val w = (if (selected) width else 18f) * local
        drawRoundRect(color, Offset(3f * u, y * u), Size(w * u, 2f * u), CornerRadius(1f * u))
    }
}
