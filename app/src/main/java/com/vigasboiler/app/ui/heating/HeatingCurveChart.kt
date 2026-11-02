package com.vigasboiler.app.ui.heating

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import com.vigasboiler.app.R
import com.vigasboiler.app.feature.HeatingCurve
import com.vigasboiler.app.ui.common.formatNumber
import com.vigasboiler.app.ui.theme.AppColors

/** Curve inputs: slope/shift for current and (optionally) edited, plus room setpoint and limits. */
data class CurveParams(
    val slope: Double,
    val shift: Double,
    val editedSlope: Double?,
    val editedShift: Double?,
    val roomSetpoint: Double,
    val minSupply: Double?,
    val maxSupply: Double?,
)

private const val T_WARM = 20.0 // left edge
private const val T_COLD = -20.0 // right edge

/**
 * Detailed heating curve in the conventional orientation: outside temperature falls from +20 °C
 * (left) to −20 °C (right), so the curve rises as it gets colder. Shows supply limits, today's
 * operating point (outside temperature, curve target and measured supply) and a touch readout.
 */
/** Sampled curves and axis range; rebuilt only when the inputs change, not per frame. */
private class CurveModel(val p: CurveParams, supplyNow: Double?) {
    val editing = p.editedSlope != null && p.editedShift != null && (p.editedSlope != p.slope || p.editedShift != p.shift)
    val samples = DoubleArray(81) { T_WARM - it * 0.5 }
    val current = DoubleArray(samples.size) { current(samples[it]) }
    val edited: DoubleArray? = if (editing) DoubleArray(samples.size) { edited(samples[it]) } else null
    val yMin: Double
    val yMax: Double

    init {
        val all = current.toList() + edited?.toList().orEmpty() + listOfNotNull(p.minSupply, p.maxSupply, supplyNow)
        yMin = floor((all.minOrNull() ?: 20.0) / 10) * 10
        yMax = ceil(((all.maxOrNull() ?: 70.0) + 2) / 10) * 10
    }

    private fun clamp(v: Double) = v.coerceIn(p.minSupply ?: Double.NEGATIVE_INFINITY, p.maxSupply ?: Double.POSITIVE_INFINITY)
    fun current(t: Double) = clamp(HeatingCurve.supplyTemperature(p.slope, p.shift, p.roomSetpoint, t))
    fun edited(t: Double) = clamp(HeatingCurve.supplyTemperature(p.editedSlope ?: p.slope, p.editedShift ?: p.shift, p.roomSetpoint, t))
}

@Composable
fun HeatingCurveChart(p: CurveParams, outsideNow: Double?, supplyNow: Double?) {
    val extra = AppColors.extra
    val primary = MaterialTheme.colorScheme.primary
    val edit = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val limitColor = MaterialTheme.colorScheme.outline
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = label)

    val model = remember(p, supplyNow) { CurveModel(p, supplyNow) }
    val editing = model.editing
    // Axis and limit labels measured once per range, not on every frame.
    val xLabels = remember(measurer, labelStyle) {
        listOf(20.0, 10.0, 0.0, -10.0, -20.0).associateWith { measurer.measure((if (it > 0) "+" else "") + formatNumber(it, 0), labelStyle) }
    }
    val yLabels = remember(measurer, labelStyle, model.yMin, model.yMax) {
        generateSequence(model.yMin) { it + 10 }.takeWhile { it <= model.yMax }.associateWith { measurer.measure(formatNumber(it, 0), labelStyle) }
    }
    val limitLabels = remember(measurer, labelStyle, p.minSupply, p.maxSupply) {
        listOfNotNull(p.maxSupply?.let { it to "max" }, p.minSupply?.let { it to "min" })
            .map { (v, name) -> v to measurer.measure("$name ${formatNumber(v, 0)}°", labelStyle) }
    }
    val dashLimit = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) }
    val dashCurrent = remember { PathEffect.dashPathEffect(floatArrayOf(16f, 12f)) }
    val dashSelect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) }

    // Selected outside temperature (defaults to today's). Read only in the readout and in the
    // draw phase, so dragging redraws the chart without recomposing the whole card.
    val selected = remember { mutableStateOf(outsideNow?.coerceIn(T_COLD, T_WARM)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Readout(selected, model, outsideNow)

        Canvas(
            Modifier.fillMaxWidth().height(260.dp)
                .pointerInput(Unit) {
                    detectTapGestures { o -> selected.value = xToTemp(o.x, size.width.toFloat(), LEFT_PAD.dp.toPx(), RIGHT_PAD.dp.toPx()) }
                }
                .pointerInput(Unit) {
                    // Horizontal only, so vertical swipes over the chart still scroll the page.
                    detectHorizontalDragGestures { change, _ -> selected.value = xToTemp(change.position.x, size.width.toFloat(), LEFT_PAD.dp.toPx(), RIGHT_PAD.dp.toPx()) }
                },
        ) {
            val plot = PlotArea(LEFT_PAD.dp.toPx(), 8.dp.toPx(), size.width - RIGHT_PAD.dp.toPx(), size.height - BOTTOM_PAD.dp.toPx(), model.yMin, model.yMax)

            // Grid: every 5 °C outside, every 10 °C supply.
            var t = T_COLD
            while (t <= T_WARM) {
                val x = plot.x(t)
                drawLine(grid.copy(alpha = if (t % 10.0 == 0.0) 0.9f else 0.4f), Offset(x, plot.top), Offset(x, plot.bottom), 1.5f)
                xLabels[t]?.let { l -> drawText(l, topLeft = Offset(x - l.size.width / 2f, plot.bottom + 6.dp.toPx())) }
                t += 5
            }
            yLabels.forEach { (y, l) ->
                val py = plot.y(y)
                drawLine(grid.copy(alpha = 0.7f), Offset(plot.left, py), Offset(plot.right, py), 1.5f)
                drawText(l, topLeft = Offset(plot.left - 6.dp.toPx() - l.size.width, py - 7.dp.toPx()))
            }
            limitLabels.forEach { (v, l) ->
                val py = plot.y(v)
                drawLine(limitColor, Offset(plot.left, py), Offset(plot.right, py), 2f, pathEffect = dashLimit)
                drawText(l, topLeft = Offset(plot.right - 4.dp.toPx() - l.size.width, py - 16.dp.toPx()))
            }

            drawPath(
                curvePath(plot, model.samples, model.current), if (editing) primary.copy(alpha = 0.45f) else primary,
                style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = if (editing) dashCurrent else null),
            )
            model.edited?.let { drawPath(curvePath(plot, model.samples, it), edit, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)) }

            // Today's operating point: curve target (filled) and measured supply (ring).
            if (outsideNow != null && outsideNow in T_COLD..T_WARM) {
                val x = plot.x(outsideNow)
                val target = if (editing) model.edited(outsideNow) else model.current(outsideNow)
                drawCircle(if (editing) edit else primary, 6.dp.toPx(), Offset(x, plot.y(target)))
                drawCircle(Color.White, 2.5.dp.toPx(), Offset(x, plot.y(target)))
                if (supplyNow != null) drawCircle(extra.hot, 7.dp.toPx(), Offset(x, plot.y(supplyNow)), style = Stroke(2.5.dp.toPx()))
            }

            selected.value?.let { s ->
                val x = plot.x(s)
                drawLine(label.copy(alpha = 0.8f), Offset(x, plot.top), Offset(x, plot.bottom), 2f, pathEffect = dashSelect)
                drawCircle(primary, 4.dp.toPx(), Offset(x, plot.y(model.current(s))))
                if (editing) drawCircle(edit, 4.dp.toPx(), Offset(x, plot.y(model.edited(s))))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = LEFT_PAD.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.curve_axis_warm), style = MaterialTheme.typography.labelSmall, color = label)
            Text(stringResource(R.string.curve_axis_outside), style = MaterialTheme.typography.labelSmall, color = label)
            Text(stringResource(R.string.curve_axis_cold), style = MaterialTheme.typography.labelSmall, color = label)
        }
        Legend(editing, hasNow = outsideNow != null, hasSupply = supplyNow != null)
        ValueTable(listOf(15.0, 10.0, 5.0, 0.0, -5.0, -10.0, -15.0), model::current, if (editing) model::edited else null)
    }
}

private const val LEFT_PAD = 34
private const val RIGHT_PAD = 8
private const val BOTTOM_PAD = 24

private class PlotArea(val left: Float, val top: Float, val right: Float, val bottom: Float, val yMin: Double, val yMax: Double) {
    fun x(t: Double) = left + ((T_WARM - t) / (T_WARM - T_COLD) * (right - left)).toFloat()
    fun y(v: Double) = bottom - ((v - yMin) / (yMax - yMin) * (bottom - top)).toFloat()
}

/** Pixel x → outside temperature, snapped to 0.5 °C. */
private fun xToTemp(x: Float, width: Float, leftPx: Float, rightPx: Float): Double {
    val fraction = ((x - leftPx) / (width - leftPx - rightPx)).coerceIn(0f, 1f)
    val t = T_WARM - fraction * (T_WARM - T_COLD)
    return (t * 2).roundToInt() / 2.0
}

private fun curvePath(plot: PlotArea, samples: DoubleArray, values: DoubleArray): Path =
    Path().apply {
        for (i in samples.indices) {
            val x = plot.x(samples[i])
            val y = plot.y(values[i])
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }

/** "At −5 °C outside → 58 °C supply (new 61 °C)". The only composable that reads the selection. */
@Composable
private fun Readout(selected: State<Double?>, model: CurveModel, outsideNow: Double?) {
    val outside = selected.value ?: return
    val curve = model.current(outside)
    val edited = if (model.editing) model.edited(outside) else null
    val isNow = outsideNow != null && kotlin.math.abs(outside - outsideNow) < 0.26
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (isNow) R.string.curve_readout_now else R.string.curve_readout_at, formatNumber(outside)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.curve_readout_supply, formatNumber(curve, 0)), style = MaterialTheme.typography.titleLarge)
        }
        if (edited != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.heat_curve_new), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                Text(stringResource(R.string.curve_readout_supply, formatNumber(edited, 0)), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun Legend(editing: Boolean, hasNow: Boolean, hasSupply: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot(stringResource(if (editing) R.string.heat_curve_current else R.string.curve_legend_curve), MaterialTheme.colorScheme.primary.copy(alpha = if (editing) 0.45f else 1f))
        if (editing) LegendDot(stringResource(R.string.heat_curve_new), MaterialTheme.colorScheme.tertiary)
        if (hasNow) LegendDot(stringResource(R.string.curve_legend_target), if (editing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
        if (hasSupply) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(AppColors.extra.hot.copy(alpha = 0.25f)))
                Text(stringResource(R.string.curve_legend_actual), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Supply temperature at fixed outside temperatures, current and new. */
@Composable
private fun ValueTable(temps: List<Double>, current: (Double) -> Double, edited: ((Double) -> Double)?) {
    val cell = MaterialTheme.typography.labelMedium
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(vertical = 8.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        @Composable
        fun row(title: String, values: List<String>, color: Color, bold: Boolean = false) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1.6f), style = cell, color = MaterialTheme.colorScheme.onSurfaceVariant)
                values.forEach {
                    Text(it, Modifier.weight(1f), style = cell.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), color = color, textAlign = TextAlign.Center)
                }
            }
        }
        row(stringResource(R.string.curve_table_outside), temps.map { (if (it > 0) "+" else "") + formatNumber(it, 0) }, MaterialTheme.colorScheme.onSurfaceVariant)
        row(stringResource(R.string.curve_table_supply), temps.map { formatNumber(current(it), 0) }, MaterialTheme.colorScheme.primary, bold = edited == null)
        if (edited != null) row(stringResource(R.string.heat_curve_new), temps.map { formatNumber(edited(it), 0) }, MaterialTheme.colorScheme.tertiary, bold = true)
    }
}
