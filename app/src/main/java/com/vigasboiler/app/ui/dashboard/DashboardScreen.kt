package com.vigasboiler.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.vigasboiler.app.R
import com.vigasboiler.app.feature.DeviceMessages
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.history.ChartKind
import com.vigasboiler.app.history.ChartRange
import com.vigasboiler.app.feature.gatewayStatus
import com.vigasboiler.app.feature.MessageLevel
import com.vigasboiler.app.feature.PropertyValue
import com.vigasboiler.app.ratelimit.RateBudget
import com.vigasboiler.app.settings.AppSettings
import com.vigasboiler.app.ui.AppViewModel
import com.vigasboiler.app.ui.Navigate
import com.vigasboiler.app.ui.common.AnimatedValueText
import com.vigasboiler.app.ui.common.BurnerFlame
import com.vigasboiler.app.ui.common.AppCard
import com.vigasboiler.app.ui.common.appear
import androidx.compose.foundation.layout.Box
import com.vigasboiler.app.ui.common.Banner
import com.vigasboiler.app.ui.common.BannerLevel
import com.vigasboiler.app.ui.common.MeterRow
import com.vigasboiler.app.ui.common.SectionHeader
import com.vigasboiler.app.ui.common.StatusChip
import com.vigasboiler.app.ui.common.ValueTile
import com.vigasboiler.app.ui.common.formatDateTime
import com.vigasboiler.app.ui.common.formatNumber
import com.vigasboiler.app.ui.common.formatTime
import com.vigasboiler.app.ui.common.formatWithUnit
import com.vigasboiler.app.ui.common.prettify
import com.vigasboiler.app.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: AppViewModel, navigate: Navigate) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refreshManually, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = snapshot
            if (s == null) {
                item { Text(stringResource(R.string.dash_no_data), Modifier.padding(16.dp)) }
            } else {
                // Tiles open their chart: the quickest way from a number to its history.
                item { Box(Modifier.appear(0)) { Hero(s) { navigate(chart(ChartKind.Temperatures)) } } }
                item { Box(Modifier.appear(1)) { Banners(s) { navigate("messages") } } }
                item { SectionHeader(stringResource(R.string.dash_section_supply), Modifier.appear(2)) }
                item { Box(Modifier.appear(3)) { SupplyTiles(s) { navigate(chart(ChartKind.Supply)) } } }
                item { SectionHeader(stringResource(R.string.dash_section_system), Modifier.appear(4)) }
                item { Box(Modifier.appear(5)) { SystemTiles(s, settings, navigate) } }
            }
            item { SectionHeader(stringResource(R.string.dash_budget_title), Modifier.appear(6)) }
            item { Box(Modifier.appear(7)) { BudgetCard(budget) } }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** Outside and room temperature, mode and the two status chips on a warm gradient. */
@Composable
private fun Hero(s: FeatureSnapshot, onClick: () -> Unit) {
    val extra = AppColors.extra
    val outside = s["heating.sensors.temperature.outside"]?.number()
    val room = s["heating.circuits.0.sensors.temperature.room"]?.number()
    val mode = s["heating.circuits.0.operating.modes.active"]?.text()
    val program = s["heating.circuits.0.operating.programs.active"]?.text()
    val burnerOn = s["heating.burners.0"]?.bool("active") == true
    val modulation = s["heating.burners.0.modulation"]?.number()
    val online = s.gatewayStatus()?.equals("online", ignoreCase = true)
    val wifi = s["tcu.wifi"]?.number("strength") ?: s.gatewayFeature("gateway.wifi")?.number("strength")
    val onHero = MaterialTheme.colorScheme.onPrimaryContainer
    // Target = temperature of the active program (normal, reduced, comfort, …).
    val target = program?.let { s["heating.circuits.0.operating.programs.$it"]?.number("temperature") }

    // Blue into teal: the one colourful surface on the screen.
    val gradient = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.background(gradient).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                // Room temperature is the headline; outside is supporting information.
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull(
                            stringResource(if (room != null) R.string.dash_room else R.string.dash_outside),
                            mode?.let(::prettify),
                            program?.let(::prettify),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = onHero,
                    )
                    AnimatedValueText(degrees(room ?: outside), HeroTemperatureStyle, color = onHero)
                    if (room != null && target != null) TargetLine(room, target)
                }
                if (room != null && outside != null) OutsideTile(outside)
            }
            // Burner: animated flame + modulation ring, so on/off is obvious at a glance.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BurnerFlame(burnerOn, modulation)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (burnerOn) R.string.dash_burner_running else R.string.dash_burner_idle),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (burnerOn) extra.hot else onHero,
                    )
                    Text(
                        if (burnerOn) stringResource(R.string.dash_burner_modulation, formatWithUnit(modulation, "percent")) else stringResource(R.string.dash_burner_idle_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (online != null) {
                    StatusChip(
                        stringResource(if (online) R.string.dash_online else R.string.dash_offline) + (wifi?.let { " · ${it.toInt()} dBm" } ?: ""),
                        R.drawable.ic_wifi,
                        if (online) extra.success else MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                stringResource(R.string.dash_last_refresh, formatTime(s.fetchedAtMillis)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun degrees(v: Double?) = v?.let { formatNumber(it) + "°" } ?: "–"

private val HeroTemperatureStyle = TextStyle(fontSize = 84.sp, lineHeight = 88.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)

/** "Target 21° · 0.3° below" with a dot coloured cold/at target/warm. */
@Composable
private fun TargetLine(room: Double, target: Double) {
    val extra = AppColors.extra
    val diff = room - target
    val (text, color) = when {
        diff <= -0.25 -> stringResource(R.string.dash_below_target, formatNumber(-diff)) to extra.cold
        diff >= 0.25 -> stringResource(R.string.dash_above_target, formatNumber(diff)) to extra.hot
        else -> stringResource(R.string.dash_at_target) to extra.success
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            stringResource(R.string.dash_target, formatNumber(target)) + " · " + text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Small translucent tile with the outside temperature. */
@Composable
private fun OutsideTile(outside: Double) {
    val cold = AppColors.extra.cold
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(R.drawable.ic_thermometer), null, Modifier.size(14.dp), tint = cold)
                Text(stringResource(R.string.dash_outside), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedValueText(degrees(outside), MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Banners(s: FeatureSnapshot, onOpen: () -> Unit) {
    val messages = DeviceMessages.bannerMessages(s, System.currentTimeMillis())
    val error = DeviceMessages.malfunction(s) || messages.any { it.level == MessageLevel.Error }
    val warnings = messages.filter { it.level == MessageLevel.Warning }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error) Banner(BannerLevel.Error, stringResource(R.string.banner_error), stringResource(R.string.banner_open), onOpen)
        val text = when (warnings.size) {
            0 -> null
            1 -> warnings.single().let {
                stringResource(R.string.banner_warning_one, prettify(it.source.name), it.code, it.timestampMillis?.let(::formatTime) ?: "–")
            }
            else -> stringResource(R.string.banner_warning, warnings.size)
        }
        if (text != null) Banner(BannerLevel.Warning, text, stringResource(R.string.banner_open), onOpen)
    }
}

@Composable
private fun SupplyTiles(s: FeatureSnapshot, onClick: () -> Unit) {
    fun temp(name: String) = s[name]?.let { formatWithUnit(it.number(), it.unit()) } ?: "–"
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ValueTile(stringResource(R.string.dash_supply_boiler), temp("heating.boiler.sensors.temperature.commonSupply"), Modifier.weight(1f), icon = R.drawable.ic_thermometer, onClick = onClick)
        ValueTile(stringResource(R.string.dash_supply_circuit), temp("heating.circuits.0.sensors.temperature.supply"), Modifier.weight(1f), icon = R.drawable.ic_thermometer, onClick = onClick)
    }
}

@Composable
private fun SystemTiles(s: FeatureSnapshot, settings: AppSettings, navigate: Navigate) {
    val pressure = s["heating.sensors.pressure.supply"]?.number()
    val outOfRange = pressure != null && (pressure < settings.pressureMinBar || pressure > settings.pressureMaxBar)
    val modulation = s["heating.burners.0.modulation"]?.number()
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ValueTile(
            stringResource(R.string.dash_pressure),
            formatWithUnit(pressure, "bar"),
            Modifier.weight(1f),
            sub = if (outOfRange) {
                stringResource(R.string.dash_pressure_out_of_range, formatNumber(settings.pressureMinBar), formatNumber(settings.pressureMaxBar))
            } else {
                stringResource(R.string.dash_pressure_range, formatNumber(settings.pressureMinBar), formatNumber(settings.pressureMaxBar))
            },
            warn = outOfRange,
            icon = R.drawable.ic_gauge,
            onClick = { navigate(chart(ChartKind.Pressure)) },
        )
        ValueTile(
            stringResource(R.string.stats_modulation),
            formatWithUnit(modulation, "percent"),
            Modifier.weight(1f),
            sub = s["heating.burners.0.statistics"]?.number("hours")?.let { stringResource(R.string.dash_burner_hours, formatNumber(it, 0)) },
            icon = R.drawable.ic_nav_heating,
            onClick = { navigate(chart(ChartKind.Modulation)) },
        )
    }
}

private fun chart(kind: ChartKind) = "chart:${kind.name}|${ChartRange.Day.name}"

@Composable
fun BudgetCard(budget: RateBudget) {
    val warn = AppColors.extra.warning
    AppCard {
        MeterRow(
            stringResource(R.string.dash_budget_10m),
            stringResource(R.string.dash_budget_left, budget.remaining10m, budget.limit10m),
            budget.used10m.toFloat() / budget.limit10m,
            if (budget.used10m >= budget.limit10m * 0.8) warn else MaterialTheme.colorScheme.primary,
        )
        MeterRow(
            stringResource(R.string.dash_budget_24h),
            stringResource(R.string.dash_budget_left, budget.remaining24h, budget.limit24h),
            budget.used24h.toFloat() / budget.limit24h,
            if (budget.used24h >= budget.limit24h * 0.8) warn else MaterialTheme.colorScheme.primary,
        )
        budget.block?.let {
            Text(
                stringResource(R.string.dash_budget_blocked, formatDateTime(it.untilMillis), it.limitName ?: "429"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (budget.block == null && budget.isSoftLimited) {
            Text(stringResource(R.string.dash_budget_soft), color = warn, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
