package si.vicare.customapp.ui

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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import si.vicare.customapp.R
import si.vicare.customapp.feature.CommandOutcome
import si.vicare.customapp.feature.CommandValidator
import si.vicare.customapp.feature.Feature
import si.vicare.customapp.feature.FeatureRepository
import si.vicare.customapp.feature.FeatureSnapshot
import si.vicare.customapp.feature.RefreshOutcome
import si.vicare.customapp.ratelimit.CallKind
import si.vicare.customapp.ratelimit.CallPermission
import si.vicare.customapp.ratelimit.RateBudget
import si.vicare.customapp.ratelimit.RateLimitTracker
import si.vicare.customapp.settings.AppSettings
import si.vicare.customapp.settings.SettingsRepository

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
) : ViewModel() {

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
            val notice = when (outcome) {
                is RefreshOutcome.Success -> null
                is RefreshOutcome.TooSoon ->
                    if (kind == CallKind.Manual) Notice(R.string.notice_too_soon, listOf((outcome.waitMillis + 999) / 1000)) else null
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

    private fun permissionNotice(permission: CallPermission, kind: CallKind): Notice? = when (permission) {
        CallPermission.Allowed -> null
        is CallPermission.Blocked -> Notice(R.string.notice_blocked, listOf(TimeArg(permission.block.untilMillis)))
        // Automatic refreshes skip quietly at the soft limit; the dashboard shows the warning.
        is CallPermission.SoftLimited -> if (kind == CallKind.Automatic) null else Notice(R.string.notice_soft_limit)
        is CallPermission.WouldExceed -> Notice(R.string.notice_would_exceed)
    }
}
