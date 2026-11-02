package com.vigasboiler.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vigasboiler.app.R
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import com.vigasboiler.app.feature.DeviceMessages
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.MessageLevel
import com.vigasboiler.app.ui.common.AccentBackdrop
import com.vigasboiler.app.ui.common.AppHeader
import com.vigasboiler.app.ui.common.AppNavBar
import com.vigasboiler.app.ui.common.NavItem
import com.vigasboiler.app.ui.common.TabIconKind
import com.vigasboiler.app.ui.common.formatTime
import com.vigasboiler.app.ui.common.prettify
import androidx.compose.runtime.CompositionLocalProvider
import com.vigasboiler.app.ui.theme.AppColors
import com.vigasboiler.app.ui.theme.LocalAccent
import com.vigasboiler.app.feature.Day
import com.vigasboiler.app.ui.check.HeatingCheckReadyDialog
import com.vigasboiler.app.ui.check.HeatingCheckScreen
import com.vigasboiler.app.ui.common.ConfirmCommandDialog
import com.vigasboiler.app.ui.common.formatDateTime
import com.vigasboiler.app.ui.dashboard.DashboardScreen
import com.vigasboiler.app.ui.device.DeviceInfoScreen
import com.vigasboiler.app.ui.discovery.DiscoveryScreen
import com.vigasboiler.app.ui.explorer.ExplorerScreen
import com.vigasboiler.app.ui.explorer.FeatureDetailScreen
import com.vigasboiler.app.ui.heating.HeatingScreen
import com.vigasboiler.app.ui.heating.ScheduleEditorScreen
import com.vigasboiler.app.history.ChartKind
import com.vigasboiler.app.history.ChartRange
import com.vigasboiler.app.ui.charts.ChartDetailScreen
import com.vigasboiler.app.ui.charts.title as chartTitle
import com.vigasboiler.app.ui.messages.MessagesScreen
import com.vigasboiler.app.ui.messages.StatusMessagesScreen
import com.vigasboiler.app.ui.more.MoreScreen
import com.vigasboiler.app.ui.settings.SettingsScreen
import com.vigasboiler.app.ui.stats.StatisticsScreen

private enum class Tab(val route: String, @StringRes val label: Int, val icon: TabIconKind) {
    Dashboard("dashboard", R.string.nav_dashboard, TabIconKind.Overview),
    Heating("heating", R.string.nav_heating, TabIconKind.Heating),
    Stats("stats", R.string.nav_stats, TabIconKind.Stats),
    Messages("messages", R.string.nav_messages, TabIconKind.Messages),
    More("more", R.string.nav_more, TabIconKind.More),
}

/** Navigation callbacks handed to screens. Routes: `explorer`, `feature:<name>`, `schedule:<feature>`, … */
fun interface Navigate {
    operator fun invoke(route: String)
}

@Composable
fun AppShell(vm: AppViewModel, onSignOut: () -> Unit) {
    var stack by rememberSaveable { mutableStateOf(listOf(Tab.Dashboard.route)) }
    val route = stack.last()
    val navigate = Navigate { stack = stack + it }
    val pop = { if (stack.size > 1) stack = stack.dropLast(1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }
    LaunchedEffect(route) { vm.trackScreen(route) }

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(Unit) {
        vm.notices.collect { notice ->
            val args = notice.args.map { if (it is TimeArg) formatDateTime(it.millis) else it }.toTypedArray()
            snackbar.showSnackbar(resources.getString(notice.text, *args))
        }
    }

    val pending by vm.pending.collectAsStateWithLifecycle()
    val executing by vm.executing.collectAsStateWithLifecycle()
    pending?.let { ConfirmCommandDialog(it, onConfirm = vm::confirm, onDismiss = vm::dismiss) }

    // Once a week of data is collected: offer the heating check (after the analytics question).
    val offerCheck by vm.offerHeatingCheck.collectAsStateWithLifecycle()
    val askConsent by vm.askAnalyticsConsent.collectAsStateWithLifecycle()
    if (offerCheck && !askConsent && pending == null) {
        HeatingCheckReadyDialog(
            onRun = { vm.heatingCheckOffered(); navigate("heating-check") },
            onLater = vm::heatingCheckOffered,
        )
    }

    val tab = Tab.entries.firstOrNull { it.route == route }
    val rootTab = Tab.entries.first { it.route == stack.first() }
    val accents = AppColors.extra.tabs
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val faults = snapshot?.let { s -> DeviceMessages.bannerMessages(s, System.currentTimeMillis()).count { it.level == MessageLevel.Error } } ?: 0

    AccentBackdrop(accents[rootTab.ordinal]) {
        Scaffold(
            containerColor = Color.Transparent,
            // A transparent container gives no content colour of its own: text would stay black in dark mode.
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = { AppHeader(titleFor(route), if (tab != null) subtitleFor(tab, snapshot) else null, onBack = if (tab == null) pop else null) },
            bottomBar = {
                AppNavBar(
                    Tab.entries.map { t ->
                        NavItem(stringResource(t.label), t.icon, accents[t.ordinal], badge = if (t == Tab.Messages) faults else 0)
                    },
                    selected = rootTab.ordinal,
                    onSelect = { i -> stack = listOf(Tab.entries[i].route) },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // Calm motion: switching tabs fades through with a small rise; going deeper or
                // back slides along the x axis (Material shared-axis), never both at once.
                AnimatedContent(
                    targetState = stack.size to route,
                    transitionSpec = {
                        val (fromDepth, _) = initialState
                        val (toDepth, _) = targetState
                        when {
                            toDepth > fromDepth ->
                                (slideInHorizontally(tween(300, easing = EmphasizedDecelerate)) { it / 6 } + fadeIn(tween(220, 60))) togetherWith
                                    (slideOutHorizontally(tween(250)) { -it / 12 } + fadeOut(tween(120)))
                            toDepth < fromDepth ->
                                (slideInHorizontally(tween(300, easing = EmphasizedDecelerate)) { -it / 12 } + fadeIn(tween(220, 60))) togetherWith
                                    (slideOutHorizontally(tween(250)) { it / 6 } + fadeOut(tween(120)))
                            else ->
                                (fadeIn(tween(240, delayMillis = 70)) + slideInVertically(tween(300, 70, EmphasizedDecelerate)) { it / 40 }) togetherWith
                                    fadeOut(tween(90))
                        }
                    },
                    label = "screen",
                ) { (_, r) ->
                    CompositionLocalProvider(LocalAccent provides accents[rootTab.ordinal]) {
                        Box(Modifier.fillMaxSize()) { Screen(r, vm, navigate, pop, onSignOut) }
                    }
                }
                if (executing) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Context line under a tab's title. */
@Composable
private fun subtitleFor(tab: Tab, s: FeatureSnapshot?): String? {
    if (s == null) return null
    return when (tab) {
        Tab.Dashboard -> {
            val hour = java.time.LocalTime.now().hour
            val greeting = stringResource(
                when (hour) {
                    in 5..11 -> R.string.header_morning
                    in 12..17 -> R.string.header_afternoon
                    else -> R.string.header_evening
                },
            )
            greeting + " · " + stringResource(R.string.header_updated, formatTime(s.fetchedAtMillis))
        }
        Tab.Heating -> listOfNotNull(
            s["heating.circuits.0.operating.modes.active"]?.text()?.let(::prettify),
            s["heating.circuits.0.operating.programs.active"]?.text()?.let { stringResource(R.string.header_program, prettify(it)) },
        ).joinToString(" · ").ifEmpty { null }
        Tab.Stats -> stringResource(R.string.header_stats)
        Tab.Messages -> {
            val faults = DeviceMessages.bannerMessages(s, System.currentTimeMillis()).count { it.level == MessageLevel.Error }
            if (faults == 0) stringResource(R.string.header_no_faults) else pluralStringResource(R.plurals.header_faults, faults, faults)
        }
        Tab.More -> stringResource(R.string.header_more)
    }
}

@Composable
private fun Screen(route: String, vm: AppViewModel, navigate: Navigate, pop: () -> Unit, onSignOut: () -> Unit) {
    when {
        route == Tab.Dashboard.route -> DashboardScreen(vm, navigate)
        route == Tab.Heating.route -> HeatingScreen(vm, navigate)
        route == Tab.Stats.route -> StatisticsScreen(vm, navigate)
        route.startsWith("chart:") -> route.removePrefix("chart:").split('|').let { parts ->
            ChartDetailScreen(ChartKind.valueOf(parts[0]), parts.getOrNull(1)?.let(ChartRange::valueOf) ?: ChartRange.Day)
        }
        route == Tab.Messages.route -> MessagesScreen(vm, navigate)
        route == "status-messages" -> StatusMessagesScreen(vm)
        route == Tab.More.route -> MoreScreen(navigate, onSignOut, vm.checkReadiness.collectAsStateWithLifecycle().value)
        route == "heating-check" -> HeatingCheckScreen(navigate, onDone = pop)
        route == "explorer" -> ExplorerScreen(vm, navigate)
        route.startsWith("feature:") -> FeatureDetailScreen(vm, route.removePrefix("feature:"), navigate)
        route.startsWith("schedule:") -> route.removePrefix("schedule:").split('|').let { parts ->
            ScheduleEditorScreen(vm, parts[0], parts.getOrNull(1)?.let { key -> Day.entries.firstOrNull { it.key == key } })
        }
        route == "settings" -> SettingsScreen(vm)
        route == "device" -> DeviceInfoScreen(vm)
        route == "discovery" -> DiscoveryScreen(onSignOut = onSignOut, showTopBar = false)
    }
}

@Composable
private fun titleFor(route: String): String = when {
    route.startsWith("feature:") -> route.removePrefix("feature:")
    route.startsWith("schedule:") -> stringResource(R.string.heat_schedule)
    route.startsWith("chart:") -> stringResource(chartTitle(ChartKind.valueOf(route.removePrefix("chart:").substringBefore('|'))))
    else -> stringResource(
        when (route) {
            "dashboard" -> R.string.nav_dashboard
            "heating" -> R.string.nav_heating
            "stats" -> R.string.nav_stats
            "messages" -> R.string.nav_messages
            "more" -> R.string.nav_more
            "explorer" -> R.string.more_explorer
            "settings" -> R.string.more_settings
            "device" -> R.string.more_device
            "discovery" -> R.string.more_discovery
            "status-messages" -> R.string.msg_info_title
            "heating-check" -> R.string.check_title
            else -> R.string.app_name
        },
    )
}
