package com.vigasboiler.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme

/** Bars at x = 0, 1, 2 … labelled with [labels]; only every [labelEvery]th bar gets a label. */
@Composable
fun ColumnChart(values: List<Double>, labels: List<String>, modifier: Modifier = Modifier, labelEvery: Int = 1) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        if (values.isNotEmpty()) producer.runTransaction { columnSeries { series(values) } }
    }
    if (values.isEmpty()) return
    val formatter = CartesianValueFormatter { _, x, _ -> labels.getOrNull(x.toInt()) ?: "" }
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            rememberCartesianChart(
                rememberColumnCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = formatter, itemPlacer = HorizontalAxis.ItemPlacer.aligned({ labelEvery })),
            ),
            producer,
            modifier.fillMaxWidth().height(200.dp),
            scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End),
            zoomState = fitAll(),
        )
    }
}

/**
 * Several line series sharing an integer x axis (bucket indices); [xLabel] formats x for the
 * bottom axis, labelling every [labelEvery]th step.
 */
@Composable
fun LineChart(series: List<List<Pair<Int, Double>>>, xLabel: (Double) -> String, modifier: Modifier = Modifier, labelEvery: Int = 1) {
    val nonEmpty = series.filter { it.isNotEmpty() }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(nonEmpty) {
        if (nonEmpty.isNotEmpty()) {
            producer.runTransaction {
                lineSeries { nonEmpty.forEach { s -> series(s.map { it.first }, s.map { it.second }) } }
            }
        }
    }
    if (nonEmpty.isEmpty()) return
    val formatter = CartesianValueFormatter { _, x, _ -> xLabel(x) }
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            rememberCartesianChart(
                rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = formatter, itemPlacer = HorizontalAxis.ItemPlacer.aligned({ labelEvery })),
            ),
            producer,
            modifier.fillMaxWidth().height(220.dp),
            scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End),
            zoomState = fitAll(),
        )
    }
}

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
