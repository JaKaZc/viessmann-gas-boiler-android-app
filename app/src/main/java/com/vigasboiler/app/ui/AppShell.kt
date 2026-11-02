package com.vigasboiler.app.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vigasboiler.app.R
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

private enum class Tab(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    Dashboard("dashboard", R.string.nav_dashboard, R.drawable.ic_nav_dashboard),
    Heating("heating", R.string.nav_heating, R.drawable.ic_nav_heating),
    Stats("stats", R.string.nav_stats, R.drawable.ic_nav_stats),
    Messages("messages", R.string.nav_messages, R.drawable.ic_nav_messages),
    More("more", R.string.nav_more, R.drawable.ic_nav_more),
}

/** Navigation callbacks handed to screens. Routes: `explorer`, `feature:<name>`, `schedule:<feature>`, … */
fun interface Navigate {
    operator fun invoke(route: String)
}

@OptIn(ExperimentalMaterial3Api::class)
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
    // Tab roots get a large title that collapses into the bar on scroll; each route keeps its own state.
    val barState = rememberSaveable(route, saver = TopAppBarState.Saver) { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
    val scrollBehavior = if (tab != null) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(barState)
    } else {
        TopAppBarDefaults.pinnedScrollBehavior(barState)
    }
    val barColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (tab != null) {
                LargeTopAppBar(
                    title = { Text(titleFor(route), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = barColors,
                    scrollBehavior = scrollBehavior,
                )
            } else {
                TopAppBar(
                    title = { Text(titleFor(route), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = barColors,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        val back = stringResource(R.string.back)
                        IconButton(onClick = pop, modifier = Modifier.semantics { contentDescription = back }) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = null)
                        }
                    },
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = stack.first() == t.route,
                        onClick = { stack = listOf(t.route) },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Depth decides the direction: deeper slides in from the right, back slides out,
            // switching tabs fades through with a slight scale.
            AnimatedContent(
                targetState = stack.size to route,
                transitionSpec = {
                    val (fromDepth, _) = initialState
                    val (toDepth, _) = targetState
                    when {
                        toDepth > fromDepth ->
                            (slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(320))) togetherWith
                                (slideOutHorizontally(tween(320)) { -it / 8 } + fadeOut(tween(200)))
                        toDepth < fromDepth ->
                            (slideInHorizontally(tween(320)) { -it / 8 } + fadeIn(tween(320))) togetherWith
                                (slideOutHorizontally(tween(320)) { it / 4 } + fadeOut(tween(200)))
                        else ->
                            (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(260, delayMillis = 60), initialScale = 0.97f)) togetherWith
                                fadeOut(tween(120))
                    }
                },
                label = "screen",
            ) { (_, r) ->
                Box(Modifier.fillMaxSize()) { Screen(r, vm, navigate, pop, onSignOut) }
            }
            if (executing) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
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
