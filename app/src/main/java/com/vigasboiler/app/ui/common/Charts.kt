package com.vigasboiler.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme

/** One line of a [TimeLineChart]: points (bucket index → value) and how it is drawn. */
data class LineSpec(
    val points: List<Pair<Int, Double>>,
    val color: Color,
    val dashed: Boolean = false,
    /** Gradient under the line, fading to transparent. */
    val area: Boolean = false,
)

/**
 * Lines sharing an integer x axis (bucket indices), smooth, each in its own colour; tapping
 * shows the values at that point. [xLabel] formats x for the bottom axis, every [labelEvery]th step.
 */
@Composable
fun TimeLineChart(
    lines: List<LineSpec>,
    xLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    labelEvery: Int = 1,
    height: Dp = 200.dp,
    decimals: Int = 1,
) {
    val shown = lines.filter { it.points.isNotEmpty() }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(shown.map { it.points }) {
        if (shown.isNotEmpty()) producer.runTransaction { lineSeries { shown.forEach { s -> series(s.points.map { it.first }, s.points.map { it.second }) } } }
    }
    if (shown.isEmpty()) return
    val styles = shown.map { Triple(it.color, it.dashed, it.area) }
    val vicoLines = remember(styles) {
        styles.map { (color, dashed, area) ->
            LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill(color)),
                stroke = if (dashed) LineCartesianLayer.LineStroke.Dashed(thickness = 1.5.dp, dashLength = 5.dp, gapLength = 4.dp)
                else LineCartesianLayer.LineStroke.Continuous(thickness = 2.5.dp),
                areaFill = if (area) LineCartesianLayer.AreaFill.single(Fill(Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0f))))) else null,
                interpolator = LineCartesianLayer.Interpolator.cubic(),
            )
        }
    }
    // Fit the y axis to the data (0 would squash 1.4–1.5 bar into a flat line at the top).
    val values = shown.flatMap { l -> l.points.map { it.second } }
    val range = remember(values.min(), values.max()) { yRange(values.min(), values.max()) }
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            rememberCartesianChart(
                rememberLineCartesianLayer(LineCartesianLayer.LineProvider.series(vicoLines), rangeProvider = range),
                startAxis = VerticalAxis.rememberStart(valueFormatter = remember(decimals) { CartesianValueFormatter { _, y, _ -> formatNumber(y, decimals) } }),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = CartesianValueFormatter { _, x, _ -> xLabel(x) }, itemPlacer = HorizontalAxis.ItemPlacer.aligned({ labelEvery })),
                marker = marker(decimals),
            ),
            producer,
            modifier.fillMaxWidth().height(height),
            scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End),
            zoomState = fitAll(),
        )
    }
}

/** Data range plus 10 % (at least 0.1) on both sides, on whole steps of a "nice" size. */
internal fun niceRange(min: Double, max: Double): Pair<Double, Double> {
    val pad = maxOf((max - min) * 0.1, 0.1)
    val step = when {
        max - min + 2 * pad > 20 -> 5.0
        max - min + 2 * pad > 5 -> 1.0
        max - min + 2 * pad > 1 -> 0.5
        else -> 0.1
    }
    // The epsilon keeps 1.3 / 0.1 = 12.999… from rounding down a whole step.
    return Math.floor((min - pad) / step + 1e-9) * step to Math.ceil((max + pad) / step - 1e-9) * step
}

private fun yRange(min: Double, max: Double): CartesianLayerRangeProvider =
    niceRange(min, max).let { (lo, hi) -> CartesianLayerRangeProvider.fixed(minY = lo, maxY = hi) }

/** Rounded columns at x = bucket index in [color]; tapping shows the value. */
@Composable
fun BarChart(
    points: List<Pair<Int, Double>>,
    color: Color,
    xLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    labelEvery: Int = 1,
    height: Dp = 200.dp,
    decimals: Int = 1,
) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        if (points.isNotEmpty()) producer.runTransaction { columnSeries { series(points.map { it.first }, points.map { it.second }) } }
    }
    if (points.isEmpty()) return
    val column = rememberLineComponent(fill = Fill(color), thickness = 10.dp, shape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            rememberCartesianChart(
                rememberColumnCartesianLayer(ColumnCartesianLayer.ColumnProvider.series(column)),
                startAxis = VerticalAxis.rememberStart(valueFormatter = remember(decimals) { CartesianValueFormatter { _, y, _ -> formatNumber(y, decimals) } }),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = CartesianValueFormatter { _, x, _ -> xLabel(x) }, itemPlacer = HorizontalAxis.ItemPlacer.aligned({ labelEvery })),
                marker = marker(decimals),
            ),
            producer,
            modifier.fillMaxWidth().height(height),
            scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End),
            zoomState = fitAll(),
        )
    }
}

/** Bars at x = 0, 1, 2 … labelled with [labels]; only every [labelEvery]th bar gets a label. */
@Composable
fun ColumnChart(values: List<Double>, labels: List<String>, modifier: Modifier = Modifier, labelEvery: Int = 1) {
    BarChart(values.mapIndexed { i, v -> i to v }, MaterialTheme.colorScheme.primary, { x -> labels.getOrNull(x.toInt()) ?: "" }, modifier, labelEvery)
}

/** Several lines in the theme's colours (first primary, then tertiary, then outline). */
@Composable
fun LineChart(series: List<List<Pair<Int, Double>>>, xLabel: (Double) -> String, modifier: Modifier = Modifier, labelEvery: Int = 1) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.outline)
    TimeLineChart(series.mapIndexed { i, s -> LineSpec(s, colors[i % colors.size]) }, xLabel, modifier, labelEvery, height = 220.dp)
}

/** Tap/drag marker: a guideline and a rounded bubble with each line's value in its colour. */
@Composable
private fun marker(decimals: Int) = rememberDefaultCartesianMarker(
    label = rememberTextComponent(
        style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        padding = Insets(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
        background = rememberShapeComponent(fill = Fill(MaterialTheme.colorScheme.surfaceContainerHighest), shape = RoundedCornerShape(8.dp)),
    ),
    valueFormatter = remember(decimals) { DefaultCartesianMarker.ValueFormatter.default(decimalCount = decimals) },
    guideline = rememberLineComponent(fill = Fill(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)), thickness = 1.dp),
)

/**
 * Shrinks the chart so every x value fits the width (a 30-day chart shows all 30 days instead
 * of scrolling); few values keep the default spacing. Anything still too wide opens at the most
 * recent end.
 */
@Composable
private fun fitAll() = rememberVicoZoomState(
    zoomEnabled = false,
    initialZoom = Zoom.min(Zoom.fixed(), Zoom.Content),
    minZoom = Zoom.min(Zoom.fixed(), Zoom.Content),
)
