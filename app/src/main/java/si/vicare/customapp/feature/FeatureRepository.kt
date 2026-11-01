package si.vicare.customapp.feature

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import si.vicare.customapp.net.ApiErrors
import si.vicare.customapp.net.ViessmannApi
import si.vicare.customapp.ratelimit.CallKind
import si.vicare.customapp.ratelimit.CallPermission
import si.vicare.customapp.ratelimit.RateLimitTracker
import si.vicare.customapp.ratelimit.RateLimitedException

/** Device and gateway features from one full refresh. */
data class FeatureSnapshot(val device: List<Feature>, val gateway: List<Feature>, val fetchedAtMillis: Long) {
    private val deviceByName = device.associateBy { it.name }
    private val gatewayByName = gateway.associateBy { it.name }
    operator fun get(name: String): Feature? = deviceByName[name]
    fun gatewayFeature(name: String): Feature? = gatewayByName[name]
}

/** Notified after every successful full refresh (history recording, notifications). */
fun interface SnapshotListener {
    suspend fun onSnapshot(previous: FeatureSnapshot?, current: FeatureSnapshot)
}

sealed interface RefreshOutcome {
    data class Success(val snapshot: FeatureSnapshot) : RefreshOutcome
    data class TooSoon(val waitMillis: Long) : RefreshOutcome
    data class NotAllowed(val permission: CallPermission) : RefreshOutcome
    data class Failed(val message: String, val httpCode: Int? = null) : RefreshOutcome
    data object NoDevice : RefreshOutcome
}

sealed interface CommandOutcome {
    /**
     * The API accepted the command. [feature] is what the app now shows (the boiler's re-read
     * value if [confirmed], otherwise the accepted value while the boiler applies it).
     */
    data class Success(val feature: Feature?, val confirmed: Boolean = true) : CommandOutcome
    data class Invalid(val errors: List<ValidationError>) : CommandOutcome
    data object NotExecutable : CommandOutcome
    data class NotAllowed(val permission: CallPermission) : CommandOutcome

    /** HTTP 403: the command exists but is not part of the Basic plan. */
    data class NotInPlan(val message: String) : CommandOutcome
    data class Failed(val message: String) : CommandOutcome
}

class FeatureRepository(
    private val api: ViessmannApi,
    private val tracker: RateLimitTracker,
    private val selectionStore: SelectionStore,
    private val apiBaseUrl: String,
    private val cacheDir: File,
    /** Discovery dump directory; its installations file resolves the selection without API calls. */
    private val discoveryDir: File,
    private val listeners: List<SnapshotListener> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Runs the delayed confirmation read after a command the boiler had not applied yet. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val confirmDelayMillis: Long = CONFIRM_DELAY_MILLIS,
) {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow<FeatureSnapshot?>(null)
    val snapshot: StateFlow<FeatureSnapshot?> = _snapshot.asStateFlow()
    private var lastFullRefreshAttempt = 0L

    /** Accepted changes the boiler has not reported yet: feature → changed values and expiry. */
    private data class Pending(val changed: Map<String, JsonElement>, val untilMillis: Long)
    private val pending = HashMap<String, Pending>()

    /** Shows the last stored snapshot immediately, without an API call. */
    suspend fun loadCached() = mutex.withLock {
        if (_snapshot.value != null) return@withLock
        withContext(Dispatchers.IO) {
            val device = File(cacheDir, DEVICE_FILE).takeIf { it.exists() }?.readText() ?: return@withContext
            val gateway = File(cacheDir, GATEWAY_FILE).takeIf { it.exists() }?.readText().orEmpty()
            val fetchedAt = File(cacheDir, META_FILE).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            _snapshot.value = FeatureSnapshot(FeatureParser.parse(device), FeatureParser.parse(gateway), fetchedAt)
            lastFullRefreshAttempt = fetchedAt
        }
    }

    suspend fun refresh(kind: CallKind): RefreshOutcome = mutex.withLock {
        val now = clock()
        if (kind != CallKind.Background && now - lastFullRefreshAttempt < MIN_INTERVAL_MILLIS) {
            return@withLock RefreshOutcome.TooSoon(MIN_INTERVAL_MILLIS - (now - lastFullRefreshAttempt))
        }
        val selection = resolveSelection(kind) ?: return@withLock RefreshOutcome.NoDevice
        tracker.check(kind, calls = 2).let { if (it != CallPermission.Allowed) return@withLock RefreshOutcome.NotAllowed(it) }
        lastFullRefreshAttempt = now
        try {
            val deviceResponse = api.deviceFeatures(selection.installationId, selection.gatewaySerial, selection.deviceId)
            val deviceBody = (deviceResponse.body() ?: deviceResponse.errorBody())?.string().orEmpty()
            if (!deviceResponse.isSuccessful) {
                return@withLock RefreshOutcome.Failed(ApiErrors.readable(deviceResponse.code(), deviceBody), deviceResponse.code())
            }
            // Gateway features are secondary (online status, Wi-Fi); their failure keeps the old ones.
            val gatewayBody = runCatching {
                api.gatewayFeatures(selection.installationId, selection.gatewaySerial)
                    .let { r -> if (r.isSuccessful) r.body()?.string() else null }
            }.getOrNull()
            val previous = _snapshot.value
            val snapshot = FeatureSnapshot(
                device = withPending(FeatureParser.parse(deviceBody), now),
                gateway = gatewayBody?.let(FeatureParser::parse) ?: previous?.gateway.orEmpty(),
                fetchedAtMillis = now,
            )
            store(snapshot)
            listeners.forEach { runCatching { it.onSnapshot(previous, snapshot) } }
            RefreshOutcome.Success(snapshot)
        } catch (e: RateLimitedException) {
            RefreshOutcome.NotAllowed(CallPermission.Blocked(e.block))
        } catch (e: IOException) {
            RefreshOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun execute(feature: Feature, commandName: String, values: Map<String, JsonElement>): CommandOutcome {
        val command = feature.commands[commandName] ?: return CommandOutcome.NotExecutable
        if (!command.isExecutable) return CommandOutcome.NotExecutable
        CommandValidator.validate(command, values).takeIf { it.isNotEmpty() }?.let { return CommandOutcome.Invalid(it) }
        if (!command.uri.startsWith(apiBaseUrl.trimEnd('/') + "/")) {
            return CommandOutcome.Failed("Refusing to send a command to an unexpected host")
        }
        // The command plus the confirming re-read.
        tracker.check(CallKind.Command, calls = 2).let { if (it != CallPermission.Allowed) return CommandOutcome.NotAllowed(it) }
        return try {
            val body = CommandValidator.body(command, values).toString().toRequestBody(JSON)
            val response = api.executeCommand(command.uri, body)
            val text = (response.body() ?: response.errorBody())?.string().orEmpty()
            when {
                response.code() == 403 -> CommandOutcome.NotInPlan(ApiErrors.readable(403, text))
                response.code() == 429 -> CommandOutcome.NotAllowed(
                    tracker.activeBlock()?.let { CallPermission.Blocked(it) } ?: CallPermission.WouldExceed(tracker.currentBudget()),
                )
                !response.isSuccessful -> CommandOutcome.Failed(ApiErrors.readable(response.code(), text))
                reportsFailure(text) -> CommandOutcome.Failed(ApiErrors.readable(response.code(), text))
                else -> accepted(feature, commandName, values)
            }
        } catch (e: RateLimitedException) {
            CommandOutcome.NotAllowed(CallPermission.Blocked(e.block))
        } catch (e: IOException) {
            CommandOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * The API accepted the command: show the new values at once, then confirm with a re-read.
     * Boilers often apply a change a few seconds after the API accepts it, so a stale re-read
     * keeps the accepted values and schedules one more read.
     */
    private suspend fun accepted(feature: Feature, commandName: String, values: Map<String, JsonElement>): CommandOutcome {
        val effect = CommandEffects.apply(feature.raw, commandName, values)
        val optimistic = FeatureParser.parseFeature(effect.raw) ?: feature
        val fetched = fetchFeature(feature.name)
        if (fetched != null && CommandEffects.confirms(fetched, effect.changed)) {
            mutex.withLock { pending.remove(feature.name); merge(fetched) }
            return CommandOutcome.Success(fetched, confirmed = true)
        }
        mutex.withLock {
            if (effect.changed.isNotEmpty()) pending[feature.name] = Pending(effect.changed, clock() + PENDING_MILLIS)
            merge(optimistic)
        }
        if (effect.changed.isNotEmpty()) scheduleConfirmation(feature.name)
        return CommandOutcome.Success(optimistic, confirmed = false)
    }

    private fun scheduleConfirmation(name: String) {
        scope.launch {
            delay(confirmDelayMillis)
            val expected = mutex.withLock { pending[name] } ?: return@launch
            // Automatic, so it respects the soft limit like any other unattended read.
            if (tracker.check(CallKind.Automatic) != CallPermission.Allowed) return@launch
            val fetched = fetchFeature(name) ?: return@launch
            if (CommandEffects.confirms(fetched, expected.changed)) {
                mutex.withLock {
                    pending.remove(name)
                    merge(fetched)
                }
            }
        }
    }

    /** Keeps accepted-but-unconfirmed values over a stale refresh until they expire. */
    private fun withPending(features: List<Feature>, now: Long): List<Feature> {
        pending.entries.removeAll { it.value.untilMillis < now }
        if (pending.isEmpty()) return features
        return features.map { f ->
            val p = pending[f.name] ?: return@map f
            if (CommandEffects.confirms(f, p.changed)) {
                pending.remove(f.name)
                f
            } else {
                FeatureParser.parseFeature(CommandEffects.withValues(f.raw, p.changed)) ?: f
            }
        }
    }

    /** Replaces one feature in the snapshot and persists it. Call with [mutex] held. */
    private suspend fun merge(updated: Feature) {
        _snapshot.value?.let { current ->
            store(current.copy(device = current.device.map { if (it.name == updated.name) updated else it }))
        }
    }

    /** Reads one feature with the `filter` parameter. */
    private suspend fun fetchFeature(name: String): Feature? {
        val selection = selectionStore.read() ?: return null
        val response = runCatching {
            api.deviceFeatures(selection.installationId, selection.gatewaySerial, selection.deviceId, filter = name)
        }.getOrNull() ?: return null
        if (!response.isSuccessful) return null
        return FeatureParser.parse(response.body()?.string().orEmpty()).firstOrNull { it.name == name }
    }

    /** Uses the stored selection, else the discovery dump, else one equipment call. */
    suspend fun resolveSelection(kind: CallKind = CallKind.Automatic): Selection? {
        selectionStore.read()?.let { return it }
        val fromDump = File(discoveryDir, "02_installations.json").takeIf { it.exists() }
            ?.let { withContext(Dispatchers.IO) { it.readText() } }
            ?.let(SelectionParser::fromInstallations)
        val selection = fromDump ?: run {
            if (tracker.check(kind) != CallPermission.Allowed) return null
            val response = try {
                api.installations()
            } catch (_: IOException) {
                return null
            }
            if (!response.isSuccessful) return null
            SelectionParser.fromInstallations(response.body()?.string().orEmpty())
        } ?: return null
        selectionStore.write(selection)
        return selection
    }

    private suspend fun store(snapshot: FeatureSnapshot) {
        _snapshot.value = snapshot
        withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            File(cacheDir, DEVICE_FILE).writeText(JsonObject(mapOf("data" to JsonArray(snapshot.device.map { it.raw }))).toString())
            File(cacheDir, GATEWAY_FILE).writeText(JsonObject(mapOf("data" to JsonArray(snapshot.gateway.map { it.raw }))).toString())
            File(cacheDir, META_FILE).writeText(snapshot.fetchedAtMillis.toString())
        }
    }

    /** Some command responses are 2xx with `{"data":{"success":false,"reason":"…"}}`. */
    private fun reportsFailure(body: String): Boolean {
        val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return false
        val data = json["data"] as? JsonObject ?: json
        return (data["success"] as? JsonPrimitive)?.booleanOrNull == false
    }

    companion object {
        const val MIN_INTERVAL_MILLIS = 60_000L
        const val CONFIRM_DELAY_MILLIS = 15_000L
        const val PENDING_MILLIS = 3 * 60_000L
        private const val DEVICE_FILE = "device_features.json"
        private const val GATEWAY_FILE = "gateway_features.json"
        private const val META_FILE = "fetched_at.txt"
        private val JSON = "application/json".toMediaType()
    }
}
