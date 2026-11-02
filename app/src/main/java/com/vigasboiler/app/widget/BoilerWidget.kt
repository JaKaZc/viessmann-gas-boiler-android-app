package com.vigasboiler.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.SystemClock
import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import android.widget.RemoteViews
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.vigasboiler.app.MainActivity
import com.vigasboiler.app.R
import com.vigasboiler.app.auth.AuthState
import com.vigasboiler.app.auth.TokenManager
import com.vigasboiler.app.feature.FeatureRepository
import com.vigasboiler.app.history.HistoryRepository
import com.vigasboiler.app.ratelimit.CallKind
import com.vigasboiler.app.settings.SettingsRepository
import com.vigasboiler.app.ui.common.formatNumber
import com.vigasboiler.app.ui.common.formatTime
import com.vigasboiler.app.ui.common.prettify
import com.vigasboiler.app.work.BackgroundRefresh

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): FeatureRepository
    fun tokenManager(): TokenManager
    fun settings(): SettingsRepository
    fun history(): HistoryRepository
}

/** True while a refresh started from the widget button runs. */
object WidgetRefresh {
    val running = MutableStateFlow(false)
}

/**
 * Home-screen overview: room temperature, burner, outside, faults and when the data was fetched
 * and will be fetched next. It never calls the API itself except for its refresh button (a
 * manual refresh, with the usual one-minute spacing and rate limits).
 */
class BoilerWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, MEDIUM, LARGE, FULL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        entry.tokenManager().initialize()
        // The widget may start the process; the app's cached snapshot is then not loaded yet.
        if (entry.repository().snapshot.value == null) entry.repository().loadCached()
        val interval = entry.settings().current().backgroundIntervalMinutes
        provideContent {
            val snapshot by entry.repository().snapshot.collectAsState()
            val auth by entry.tokenManager().state.collectAsState()
            val nextRun by remember { BackgroundRefresh.nextRun(context) }.collectAsState(null)
            val refreshing by WidgetRefresh.running.collectAsState()
            val data = snapshot?.let { WidgetData.from(it, nextRun, interval, System.currentTimeMillis()) }
            LaunchedEffect(data?.nextRefreshMillis) { data?.let { WidgetAlarm.schedule(context, it.nextRefreshMillis + WidgetAlarm.GRACE_MILLIS) } }
            Content(data, signedIn = auth == AuthState.SignedIn, refreshing = refreshing)
        }
    }

    companion object {
        private val COMPACT = DpSize(110.dp, 50.dp)

        /** Re-renders every placed widget, overview and statistics, from stored data (no API call). */
        suspend fun redraw(context: Context) {
            BoilerWidget().updateAll(context)
            StatsWidget().updateAll(context)
        }

        /** Whether the launcher can add the widget from inside the app. */
        fun canPin(context: Context): Boolean = AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

        /** Asks the launcher to add a widget ([BoilerWidgetReceiver] or [StatsWidgetReceiver]); it shows its own confirmation. */
        fun requestPin(context: Context, receiver: Class<*> = BoilerWidgetReceiver::class.java) {
            AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, receiver), null, null)
        }
    }
}

class BoilerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = BoilerWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetAlarm.ACTION_REDRAW) return super.onReceive(context, intent)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                BoilerWidget.redraw(context)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetAlarm.cancel(context)
    }
}

/**
 * Redraws the widget shortly after the expected background refresh. The alarm does not wake the
 * phone: it is delivered once the screen is on again, so a refresh that Android postponed (Doze)
 * does not leave the countdown running below zero.
 */
object WidgetAlarm {
    const val ACTION_REDRAW = "com.vigasboiler.app.widget.REDRAW"
    const val GRACE_MILLIS = 60_000L
    private const val WINDOW_MILLIS = 5 * 60_000L

    fun schedule(context: Context, atMillis: Long) {
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, atMillis, WINDOW_MILLIS, intent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(intent(context))
    }

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, BoilerWidgetReceiver::class.java).setAction(ACTION_REDRAW),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Refresh button: one manual refresh; the snapshot listener redraws the widget on success. */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        if (WidgetRefresh.running.value) return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        WidgetRefresh.running.value = true
        BoilerWidget.redraw(context)
        try {
            entry.repository().refresh(CallKind.Manual)
        } finally {
            WidgetRefresh.running.value = false
            BoilerWidget.redraw(context)
        }
    }
}

@Composable
private fun Content(data: WidgetData?, signedIn: Boolean, refreshing: Boolean) {
    val size = LocalSize.current
    Box(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(if (data?.burnerOn == true && signedIn) R.drawable.widget_background_burning else R.drawable.widget_background))
            .clickable(actionStartActivity<MainActivity>())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when {
            !signedIn -> Message(R.string.widget_signed_out)
            data == null -> Message(R.string.widget_no_data)
            size.width < MEDIUM.width || size.height < MEDIUM.height -> Compact(data, refreshing)
            size.height < LARGE.height -> Medium(data, refreshing)
            else -> Hero(data, refreshing, tiles = size.height >= FULL.height)
        }
    }
}

// Heights each layout needs (padding 24 dp included); a smaller widget gets the next smaller
// layout instead of clipping.
private val MEDIUM = DpSize(250.dp, 150.dp)
private val LARGE = DpSize(250.dp, 270.dp)
private val FULL = DpSize(250.dp, 340.dp)

@Composable
private fun Message(text: Int) {
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(ImageProvider(R.drawable.ic_nav_heating), null, GlanceModifier.size(24.dp), colorFilter = ColorFilter.tint(WidgetColors.hot))
        Spacer(GlanceModifier.height(6.dp))
        Label(LocalContext.current.getString(text), 13.sp, WidgetColors.onBg)
    }
}

/** 2×1 / 2×2: room temperature, burner ring, outside and update time. */
@Composable
private fun Compact(d: WidgetData, refreshing: Boolean) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(GlanceModifier.defaultWeight()) {
            Label(degrees(d.room ?: d.outside), 32.sp, WidgetColors.onBg)
            Label(
                if (refreshing) context.getString(R.string.widget_refreshing)
                else listOfNotNull(d.outside?.takeIf { d.room != null }?.let { context.getString(R.string.widget_outside_short, degrees(it)) }, formatTime(d.updatedAtMillis)).joinToString(" · "),
                11.sp, if (d.stale && !refreshing) WidgetColors.warning else WidgetColors.onBgVariant,
            )
        }
        BurnerRing(d, 40)
    }
}

/** 4×2: the Overview card's headline (label, temperature, target) with the burner ring. */
@Composable
private fun Medium(d: WidgetData, refreshing: Boolean) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
                Label(
                    listOfNotNull(headline(d), d.outside?.takeIf { d.room != null }?.let { context.getString(R.string.widget_outside_short, degrees(it)) }).joinToString(" · "),
                    12.sp, WidgetColors.onBg, FontWeight.Medium,
                )
                Label(degrees(d.room ?: d.outside), 44.sp, WidgetColors.onBg)
                TargetLine(d)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BurnerRing(d, 52)
                Label(if (d.burnerOn) d.modulation?.let { "${formatNumber(it, 0)} %" } ?: "–" else context.getString(R.string.widget_off), 12.sp, if (d.burnerOn) WidgetColors.hot else WidgetColors.onBgVariant, FontWeight.Medium)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Footer(d, refreshing)
    }
}

/** About 4×3 and up: the Overview card itself, plus supply and pressure tiles when tall enough. */
@Composable
private fun Hero(d: WidgetData, refreshing: Boolean, tiles: Boolean) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxSize()) {
        Row(GlanceModifier.fillMaxWidth()) {
            Column(GlanceModifier.defaultWeight()) {
                Label(headline(d), 13.sp, WidgetColors.onBg, FontWeight.Medium)
                Label(degrees(d.room ?: d.outside), 52.sp, WidgetColors.onBg)
                TargetLine(d)
            }
            if (d.room != null && d.outside != null) OutsideTile(d.outside)
        }
        Spacer(GlanceModifier.height(12.dp))
        // Burner: flame in the modulation ring, as on the Overview screen.
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BurnerRing(d, 52)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Label(context.getString(if (d.burnerOn) R.string.dash_burner_running else R.string.dash_burner_idle), 15.sp, if (d.burnerOn) WidgetColors.hot else WidgetColors.onBg, FontWeight.Medium)
                Label(
                    if (d.burnerOn) context.getString(R.string.dash_burner_modulation, d.modulation?.let { formatNumber(it) + " %" } ?: "–") else context.getString(R.string.dash_burner_idle_sub),
                    12.sp, WidgetColors.onBgVariant,
                )
            }
        }
        Spacer(GlanceModifier.height(10.dp))
        StatusChip(d)
        if (tiles) {
            Spacer(GlanceModifier.height(10.dp))
            Row(GlanceModifier.fillMaxWidth()) {
                Tile(context.getString(R.string.widget_supply), degrees(d.supply), GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(8.dp))
                Tile(context.getString(R.string.widget_pressure), d.pressure?.let { "${formatNumber(it)} bar" } ?: "–", GlanceModifier.defaultWeight())
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Footer(d, refreshing)
    }
}

/** "Room · Heating · Normal", as on the Overview card. */
@Composable
private fun headline(d: WidgetData): String {
    val context = LocalContext.current
    return listOfNotNull(
        context.getString(if (d.room != null) R.string.dash_room else R.string.dash_outside),
        d.mode?.let(::prettify),
        d.program?.let(::prettify),
    ).joinToString(" · ")
}

/** "Target 21° · 0.3° below" with a dot coloured cold / at target / warm; a fault replaces it. */
@Composable
private fun TargetLine(d: WidgetData) {
    val context = LocalContext.current
    if (d.fault != null) {
        Label(context.getString(R.string.widget_fault, d.fault), 13.sp, WidgetColors.error, FontWeight.Medium)
        return
    }
    val room = d.room ?: return
    val target = d.target ?: return
    val diff = room - target
    val (text, dot) = when {
        diff <= -0.25 -> context.getString(R.string.dash_below_target, formatNumber(-diff)) to WidgetColors.cold
        diff >= 0.25 -> context.getString(R.string.dash_above_target, formatNumber(diff)) to WidgetColors.hot
        else -> context.getString(R.string.dash_at_target) to WidgetColors.ok
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(R.drawable.widget_dot), null, GlanceModifier.size(8.dp), colorFilter = ColorFilter.tint(dot))
        Spacer(GlanceModifier.width(7.dp))
        Label(context.getString(R.string.dash_target, formatNumber(target)) + " · " + text, 13.sp, WidgetColors.onBg)
    }
}

/** Translucent tile with the outside temperature, top right of the card. */
@Composable
private fun OutsideTile(outside: Double) {
    val context = LocalContext.current
    Column(
        GlanceModifier.background(ImageProvider(R.drawable.widget_tile)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_thermometer), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(WidgetColors.cold))
            Spacer(GlanceModifier.width(4.dp))
            Label(context.getString(R.string.dash_outside), 12.sp, WidgetColors.onBgVariant)
        }
        Label(degrees(outside), 22.sp, WidgetColors.onBg, FontWeight.Medium)
    }
}

/** Green "Online · -37 dBm", or red when the gateway is offline. */
@Composable
private fun StatusChip(d: WidgetData) {
    val online = d.online ?: return
    val context = LocalContext.current
    val tint = if (online) WidgetColors.ok else WidgetColors.error
    Row(
        GlanceModifier.background(ImageProvider(if (online) R.drawable.widget_chip_ok else R.drawable.widget_chip_error)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(R.drawable.ic_wifi), null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(tint))
        Spacer(GlanceModifier.width(6.dp))
        Label(context.getString(if (online) R.string.dash_online else R.string.dash_offline) + (d.wifiDbm?.let { " · ${it.toInt()} dBm" } ?: ""), 13.sp, tint, FontWeight.Medium)
    }
}

/** When the data is from and when the background refresh comes next, plus the refresh button. */
@Composable
private fun Footer(d: WidgetData, refreshing: Boolean) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val text = when {
            refreshing -> context.getString(R.string.widget_refreshing)
            d.stale -> context.getString(R.string.widget_stale, formatTime(d.updatedAtMillis))
            else -> context.getString(R.string.widget_updated, formatTime(d.updatedAtMillis))
        }
        Label(text, 12.sp, if (d.stale && !refreshing) WidgetColors.warning else WidgetColors.onBgVariant)
        if (!refreshing) {
            Spacer(GlanceModifier.width(4.dp))
            Countdown(d.nextRefreshMillis)
        }
        Spacer(GlanceModifier.defaultWeight())
        // A 40 dp button of its own: a small icon is easy to miss, and the miss opens the app.
        Box(
            GlanceModifier.size(40.dp).background(ImageProvider(R.drawable.widget_button)).clickable(actionRunCallback<RefreshWidgetAction>()),
            contentAlignment = Alignment.Center,
        ) {
            if (refreshing) {
                CircularProgressIndicator(GlanceModifier.size(20.dp), color = WidgetColors.onBg)
            } else {
                Image(ImageProvider(R.drawable.ic_refresh), context.getString(R.string.widget_refresh), GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(WidgetColors.onBg))
            }
        }
    }
}

/** "11:42" counting down to [targetMillis], ticked by the launcher (layout/widget_countdown.xml). */
@Composable
private fun Countdown(targetMillis: Long) {
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, R.layout.widget_countdown).apply {
        // Chronometer bases are on the elapsed-realtime clock.
        setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime() + (targetMillis - System.currentTimeMillis()), null, true)
        setChronometerCountDown(R.id.widget_countdown, true)
    }
    // A fixed box: wrap-content remote views take more room than the text and push the button out.
    AndroidRemoteViews(views, GlanceModifier.width(56.dp).height(20.dp))
}

@Composable
private fun Tile(label: String, value: String, modifier: GlanceModifier) {
    Column(modifier.background(ImageProvider(R.drawable.widget_tile)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Label(label, 11.sp, WidgetColors.onBgVariant)
        Label(value, 15.sp, WidgetColors.onBg, FontWeight.Medium)
    }
}

/**
 * The Overview card's burner: the modulation ring with a flickering flame inside while the burner
 * runs (layout/widget_burner_on.xml), an empty ring with a grey flame when it is off.
 */
@Composable
private fun BurnerRing(d: WidgetData, sizeDp: Int) {
    val context = LocalContext.current
    val views = if (d.burnerOn) {
        RemoteViews(context.packageName, R.layout.widget_burner_on).apply {
            setProgressBar(R.id.widget_ring, 100, (d.modulation ?: 0.0).roundToInt().coerceIn(0, 100), false)
        }
    } else {
        RemoteViews(context.packageName, R.layout.widget_burner_off)
    }
    AndroidRemoteViews(views, GlanceModifier.size(sizeDp.dp))
}

@Composable
private fun Label(text: String, size: TextUnit, color: ColorProvider, weight: FontWeight = FontWeight.Normal, modifier: GlanceModifier = GlanceModifier) {
    Text(text, modifier, style = TextStyle(color = color, fontSize = size, fontWeight = weight), maxLines = 1)
}

/**
 * Text and icon colours (day/night, matching ui/theme/Theme.kt); Glance picks one by the
 * launcher's light or dark mode. Backgrounds are drawables using colors_widget.xml (values and
 * values-night).
 */
internal object WidgetColors {
    val onBg = ColorProvider(day = Color(0xFF00285C), night = Color(0xFFDCE8FF))
    val onBgVariant = ColorProvider(day = Color(0xFF3B5578), night = Color(0xFFB7CCE6))
    val hot = ColorProvider(day = Color(0xFFE2600F), night = Color(0xFFFF9F5A))
    val cold = ColorProvider(day = Color(0xFF0891B2), night = Color(0xFF67D4EA))
    val ok = ColorProvider(day = Color(0xFF1E8E3E), night = Color(0xFF7EDB8F))
    val warning = ColorProvider(day = Color(0xFFB26A00), night = Color(0xFFFFC25C))
    val error = ColorProvider(day = Color(0xFFD32F2F), night = Color(0xFFFFB4AB))
}

private fun degrees(value: Double?): String = value?.let { formatNumber(it) + "°" } ?: "–"
