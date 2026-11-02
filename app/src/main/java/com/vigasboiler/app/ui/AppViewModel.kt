package com.vigasboiler.app.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import com.vigasboiler.app.R
import com.vigasboiler.app.analysis.HeatingCheck
import com.vigasboiler.app.analysis.HeatingCheckRepository
import com.vigasboiler.app.analytics.Analytics
import com.vigasboiler.app.analytics.AnalyticsEvents
import com.vigasboiler.app.analytics.AnalyticsNames
import com.vigasboiler.app.analytics.AnalyticsParams
import com.vigasboiler.app.feature.CommandOutcome
import com.vigasboiler.app.feature.CommandValidator
import com.vigasboiler.app.feature.Feature
import com.vigasboiler.app.feature.FeatureRepository
import com.vigasboiler.app.feature.FeatureSnapshot
import com.vigasboiler.app.feature.RefreshOutcome
import com.vigasboiler.app.ratelimit.CallKind
import com.vigasboiler.app.ratelimit.CallPermission
import com.vigasboiler.app.ratelimit.RateBudget
import com.vigasboiler.app.ratelimit.RateLimitTracker
import com.vigasboiler.app.settings.AppSettings
import com.vigasboiler.app.settings.SettingsRepository

/** One line of the confirmation dialog: what changes from [old] to [new]. */
data class Change(val label: String, val old: String, val new: String)

/** A command waiting for the user's confirmation. */
data class PendingCommand(
    val feature: Feature,
    val command: String,
    val values: Map<String, JsonElement>,
    val changes: List<Change>,
)

/** A snackbar text, resolved from resources in the UI. */
data class Notice(@StringRes val text: Int, val args: List<Any> = emptyList())

/** Notice argument shown as a local date and time. */
data class TimeArg(val millis: Long)

/**
 * Activity-wide state shared by all screens: the latest snapshot, the API budget, refreshes and
 * the confirm-then-send flow for every write.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: FeatureRepository,
    private val tracker: RateLimitTracker,
    private val settingsRepository: SettingsRepository,
    private val analytics: Analytics,
    heatingCheck: HeatingCheckRepository,
) : ViewModel() {

    /** Days collected for the heating check (null until read). */
    val checkReadiness: StateFlow<HeatingCheck.Readiness?> = heatingCheck.readiness.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Offer the heating check once, as soon as a week of data is there. */
    val offerHeatingCheck: StateFlow<Boolean> = combine(heatingCheck.readiness, settingsRepository.settings) { r, s -> r.ready && !s.heatingCheckOffered }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun heatingCheckOffered() = updateSettings { it.copy(heatingCheckOffered = true) }

    /**
     * Ask for consent only where analytics can actually run (live release with Firebase config),
     * and only once the stored answer has been read: starts false so the dialog never flashes.
     */
    val askAnalyticsConsent: StateFlow<Boolean> = settingsRepository.settings
        .map { analytics.isAvailable && it.analyticsConsent == null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val analyticsAvailable: Boolean get() = analytics.isAvailable

    fun setAnalyticsConsent(consent: Boolean) = updateSettings { it.copy(analyticsConsent = consent) }

    fun trackScreen(route: String) = analytics.screen(AnalyticsNames.screen(route))

    fun track(event: String, params: Map<String, String> = emptyMap()) = analytics.event(event, params)

    val snapshot: StateFlow<FeatureSnapshot?> = repository.snapshot
    val budget: StateFlow<RateBudget> = tracker.budget
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _pending = MutableStateFlow<PendingCommand?>(null)
    val pending: StateFlow<PendingCommand?> = _pending.asStateFlow()

    private val _executing = MutableStateFlow(false)
    val executing: StateFlow<Boolean> = _executing.asStateFlow()

    private val _notices = Channel<Notice>(Channel.BUFFERED)
    val notices = _notices.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.loadCached()
            tracker.currentBudget()
        }
    }

    /** Screen became visible: refresh unless the last one was under a minute ago (silently). */
    fun onForeground() = refresh(CallKind.Automatic)

    /** Pull-to-refresh. */
    fun refreshManually() = refresh(CallKind.Manual)

    private fun refresh(kind: CallKind) {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val outcome = repository.refresh(kind)
            _refreshing.value = false
            if (outcome is RefreshOutcome.NotAllowed) {
                // Names spelled out: R8 shortens class names in release builds.
                val limit = when (outcome.permission) {
                    CallPermission.Allowed -> null
                    is CallPermission.Blocked -> "blocked_429"
                    is CallPermission.SoftLimited -> "soft_limit"
                    is CallPermission.WouldExceed -> "would_exceed"
                }
                limit?.let { analytics.event(AnalyticsEvents.REFRESH_LIMITED, mapOf(AnalyticsParams.KIND to it)) }
            }
            val notice = when (outcome) {
                is RefreshOutcome.Success -> null
                is RefreshOutcome.TooSoon -> null // automatic refreshes only; skipped silently
                is RefreshOutcome.NotAllowed -> permissionNotice(outcome.permission, kind)
                is RefreshOutcome.Failed -> Notice(R.string.notice_refresh_failed, listOf(outcome.message))
                RefreshOutcome.NoDevice -> Notice(R.string.notice_no_device)
            }
            notice?.let { _notices.send(it) }
        }
    }

    /** Validates locally, then asks for confirmation. Nothing is sent before [confirm]. */
    fun request(feature: Feature, command: String, values: Map<String, JsonElement>, changes: List<Change>) {
        val cmd = feature.commands[command]
        if (cmd == null || !cmd.isExecutable) {
            viewModelScope.launch { _notices.send(Notice(R.string.notice_not_executable)) }
            return
        }
        val errors = CommandValidator.validate(cmd, values)
        if (errors.isNotEmpty()) {
            viewModelScope.launch { _notices.send(Notice(R.string.notice_invalid, listOf(errors.joinToString { "${it.param}: ${it.kind} ${it.detail}".trim() }))) }
            return
        }
        _pending.value = PendingCommand(feature, command, values, changes)
    }

    fun dismiss() {
        _pending.value = null
    }

    fun confirm() {
        val pending = _pending.value ?: return
        _pending.value = null
        _executing.value = true
        viewModelScope.launch {
            val outcome = repository.execute(pending.feature, pending.command, pending.values)
            _executing.value = false
            analytics.event(
                AnalyticsEvents.COMMAND,
                mapOf(
                    AnalyticsParams.FEATURE to pending.feature.name,
                    AnalyticsParams.COMMAND to pending.command,
                    AnalyticsParams.OUTCOME to outcomeName(outcome),
                ),
            )
            _notices.send(
                when (outcome) {
                    is CommandOutcome.Success ->
                        if (outcome.confirmed) Notice(R.string.notice_saved) else Notice(R.string.notice_saved_unconfirmed)
                    is CommandOutcome.Invalid -> Notice(R.string.notice_invalid, listOf(outcome.errors.joinToString { "${it.param}: ${it.kind}" }))
                    CommandOutcome.NotExecutable -> Notice(R.string.notice_not_executable)
                    is CommandOutcome.NotAllowed -> permissionNotice(outcome.permission, CallKind.Command) ?: Notice(R.string.notice_would_exceed)
                    is CommandOutcome.NotInPlan -> Notice(R.string.notice_not_in_plan, listOf(outcome.message))
                    is CommandOutcome.Failed -> Notice(R.string.notice_command_failed, listOf(outcome.message))
                },
            )
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    private fun outcomeName(outcome: CommandOutcome): String = when (outcome) {
        is CommandOutcome.Success -> if (outcome.confirmed) "success" else "unconfirmed"
        is CommandOutcome.Invalid -> "invalid"
        CommandOutcome.NotExecutable -> "not_executable"
        is CommandOutcome.NotAllowed -> "rate_limited"
        is CommandOutcome.NotInPlan -> "not_in_plan"
        is CommandOutcome.Failed -> "failed"
    }

    private fun permissionNotice(permission: CallPermission, kind: CallKind): Notice? = when (permission) {
        CallPermission.Allowed -> null
        is CallPermission.Blocked -> Notice(R.string.notice_blocked, listOf(TimeArg(permission.block.untilMillis)))
        // Automatic refreshes skip quietly at the soft limit; the dashboard shows the warning.
        is CallPermission.SoftLimited -> if (kind == CallKind.Automatic) null else Notice(R.string.notice_soft_limit)
        is CallPermission.WouldExceed -> Notice(R.string.notice_would_exceed)
    }
}
