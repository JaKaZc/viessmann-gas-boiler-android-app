package com.vigasboiler.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.vigasboiler.app.MainActivity
import com.vigasboiler.app.R
import com.vigasboiler.app.analysis.HeatingCheck.Series
import com.vigasboiler.app.auth.AuthState
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.history.BurnerDaily
import com.vigasboiler.app.history.HistoryRepository
import com.vigasboiler.app.ui.common.formatNumber
import com.vigasboiler.app.ui.common.formatTime

/**
 * Statistics on the home screen: gas, heat, electricity, burner and outside figures as a grid of
 * tiles; as many rows as the widget is tall. Same 4×3 default as [BoilerWidget], so the two can be
 * stacked. Draws only stored data and never calls the API.
 */
class StatsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, ONE_ROW, TWO_ROWS, THREE_ROWS, FOUR_ROWS, FIVE_ROWS))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        entry.tokenManager().initialize()
        if (entry.repository().snapshot.value == null) entry.repository().loadCached()
        val history = entry.history()
        val kwhPerM3 = entry.settings().current().gasKwhPerCubicMeter
        provideContent {
            val snapshot by entry.repository().snapshot.collectAsState()
            val auth by entry.tokenManager().state.collectAsState()
            // Recomputed for every new snapshot; history queries run off the main thread.
            val data by produceState<StatsWidgetData?>(null, snapshot?.fetchedAtMillis) {
                value = snapshot?.let { s -> withContext(Dispatchers.Default) { load(history, s, kwhPerM3) } }
            }
            StatsContent(data, signedIn = auth == AuthState.SignedIn)
        }
    }

    private suspend fun load(history: HistoryRepository, snapshot: FeatureSnapshot, kwhPerM3: Double): StatsWidgetData {
        val zone = ZoneId.systemDefault()
        val since = System.currentTimeMillis() - 8 * 24 * 3_600_000L
        suspend fun rows(series: Series) = history.series(series.feature, series.property, since).first()
        return StatsWidgetData.from(
            snapshot,
            StatsWidgetData.History(
                hours = history.series(BurnerDaily.FEATURE, "hours", since).first(),
                starts = history.series(BurnerDaily.FEATURE, "starts", since).first(),
                burnerActive = rows(Series.BurnerActive),
                modulation = rows(Series.Modulation),
                outside = rows(Series.Outside),
                gasToday = rows(Series.GasToday),
            ),
            kwhPerM3, zone, LocalDate.now(zone),
        )
    }

    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val ONE_ROW = DpSize(250.dp, 110.dp)
        private val TWO_ROWS = DpSize(250.dp, 170.dp)
        private val THREE_ROWS = DpSize(250.dp, 230.dp)
        private val FOUR_ROWS = DpSize(250.dp, 290.dp)
        private val FIVE_ROWS = DpSize(250.dp, 350.dp)
    }
}

class StatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = StatsWidget()
}

// Tile: three text lines (≈ 46 dp) + 8 dp padding, plus the 6 dp gap above it.
private val TILE_HEIGHT = 60.dp
private val HEADER_HEIGHT = 22.dp

@Composable
private fun StatsContent(d: StatsWidgetData?, signedIn: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_background))
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (!signedIn || d == null) {
            Text(
                context.getString(if (!signedIn) R.string.widget_signed_out else R.string.widget_no_data),
                style = TextStyle(color = WidgetColors.onBg, fontSize = 13.sp),
            )
            return@Box
        }
        val columns = if (size.width < 250.dp) 2 else 3
        val rows = ((size.height - 20.dp - HEADER_HEIGHT) / TILE_HEIGHT).toInt().coerceAtLeast(1)
        val tiles = tiles(d).take(columns * rows)
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.Top) {
                Text(context.getString(R.string.statsw_title), GlanceModifier.defaultWeight(), style = TextStyle(color = WidgetColors.onBg, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(context.getString(R.string.statsw_updated, formatTime(d.updatedAtMillis)), style = TextStyle(color = WidgetColors.onBgVariant, fontSize = 11.sp), maxLines = 1)
            }
            // Gaps as padding, not Spacers: a Glance Column takes at most 10 children.
            tiles.chunked(columns).forEach { row ->
                // Rows share the height, so the grid fills the widget instead of leaving a gap below.
                Row(GlanceModifier.fillMaxWidth().defaultWeight().padding(top = 6.dp)) {
                    row.forEachIndexed { j, tile ->
                        if (j > 0) Spacer(GlanceModifier.width(6.dp))
                        StatTile(tile, GlanceModifier.defaultWeight())
                    }
                    // Keep tiles the same width in a short last row.
                    repeat(columns - row.size) {
                        Spacer(GlanceModifier.width(6.dp))
                        Spacer(GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

private class Tile(val label: String, val value: String, val sub: String?, val accent: ColorProvider? = null)

/** Most useful first: the widget shows as many as fit. */
@Composable
private fun tiles(d: StatsWidgetData): List<Tile> {
    val c = LocalContext.current
    fun n(v: Double?, decimals: Int = 1) = v?.let { formatNumber(it, decimals) } ?: "–"
    fun m3(v: Double?) = v?.let { "${formatNumber(it)} m³" } ?: "–"
    fun kwhOfGas(v: Double?) = v?.let { "${formatNumber(it * d.kwhPerCubicMeter, 0)} kWh" }
    fun kwh(v: Double?) = v?.let { "${formatNumber(it)} kWh" } ?: "–"
    return listOfNotNull(
        Tile(c.getString(R.string.statsw_gas_today), m3(d.gasToday), kwhOfGas(d.gasToday), WidgetColors.hot),
        Tile(c.getString(R.string.statsw_gas_7), m3(d.gas7Days), kwhOfGas(d.gas7Days), WidgetColors.hot),
        Tile(
            c.getString(R.string.statsw_gas_month), m3(d.gasMonth),
            d.monthChangePercent?.let { c.getString(R.string.statsw_vs_last_month, (if (it >= 0) "+" else "") + formatNumber(it, 0)) } ?: kwhOfGas(d.gasMonth),
            WidgetColors.hot,
        ),
        Tile(
            c.getString(R.string.statsw_burner_today), d.burnerHoursToday?.let { "${n(it, 0)} h" } ?: "–",
            d.burnerStartsToday?.let { c.getString(R.string.statsw_starts, n(it, 0)) },
        ),
        Tile(
            c.getString(R.string.statsw_burner_7), d.avgHours7?.let { c.getString(R.string.statsw_per_day, "${n(it)} h") } ?: "–",
            d.avgStarts7?.let { c.getString(R.string.statsw_starts_per_day, n(it)) },
        ),
        Tile(
            c.getString(R.string.statsw_running), d.runningShareToday?.let { "${formatNumber(it * 100, 0)} %" } ?: "–",
            d.avgModulationToday?.let { c.getString(R.string.statsw_modulation_avg, formatNumber(it, 0)) },
        ),
        Tile(c.getString(R.string.statsw_heat_today), kwh(d.heatToday), d.efficiency?.let { c.getString(R.string.statsw_efficiency, formatNumber(it * 100, 0)) }),
        Tile(
            c.getString(R.string.statsw_outside_today),
            if (d.outsideMinToday != null && d.outsideMaxToday != null) "${n(d.outsideMinToday)}…${n(d.outsideMaxToday)}°" else "–",
            d.outsideAvg7?.let { c.getString(R.string.statsw_avg7, "${n(it)}°") },
            WidgetColors.cold,
        ),
        Tile(c.getString(R.string.statsw_power_today), kwh(d.powerToday), d.power7Days?.let { c.getString(R.string.statsw_in_7, kwh(it)) }),
        Tile(c.getString(R.string.statsw_starts_per_hour), n(d.startsPerHour7, 2), c.getString(R.string.statsw_last_7)),
        Tile(c.getString(R.string.statsw_degree_day), d.gasPerDegreeDay?.let { "${formatNumber(it, 2)} m³" } ?: "–", c.getString(R.string.statsw_degree_day_sub)),
        Tile(c.getString(R.string.statsw_gas_year), m3(d.gasYear), kwhOfGas(d.gasYear), WidgetColors.hot),
        Tile(c.getString(R.string.statsw_heat_7), kwh(d.heat7Days), null),
        Tile(c.getString(R.string.statsw_total), d.totalHours?.let { "${n(it, 0)} h" } ?: "–", d.totalStarts?.let { c.getString(R.string.statsw_starts, n(it, 0)) }),
    )
}

@Composable
private fun StatTile(t: Tile, modifier: GlanceModifier) {
    Column(modifier.fillMaxHeight().background(ImageProvider(R.drawable.widget_tile)).padding(horizontal = 9.dp, vertical = 4.dp)) {
        Label(t.label, 10.sp, WidgetColors.onBgVariant)
        Label(t.value, 15.sp, t.accent ?: WidgetColors.onBg, FontWeight.Medium)
        t.sub?.let { Label(it, 10.sp, WidgetColors.onBgVariant) }
    }
}

@Composable
private fun Label(text: String, size: TextUnit, color: ColorProvider, weight: FontWeight = FontWeight.Normal) {
    Text(text, style = TextStyle(color = color, fontSize = size, fontWeight = weight), maxLines = 1)
}
