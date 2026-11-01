package si.vicare.customapp.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import si.vicare.customapp.MainActivity
import si.vicare.customapp.R
import si.vicare.customapp.feature.FeatureSnapshot
import si.vicare.customapp.feature.MessageLevel
import si.vicare.customapp.feature.SnapshotListener
import si.vicare.customapp.ratelimit.RateBudget
import si.vicare.customapp.settings.SettingsRepository
import si.vicare.customapp.ui.common.formatDateTime
import si.vicare.customapp.ui.common.formatNumber

private val Context.notifierDataStore: DataStore<Preferences> by preferencesDataStore(name = "notifier")

/** Applies [NotificationRules] after each snapshot and posts the resulting notifications. */
class Notifier(private val context: Context, private val settings: SettingsRepository) : SnapshotListener {

    private val mutex = Mutex()

    override suspend fun onSnapshot(previous: FeatureSnapshot?, current: FeatureSnapshot) = update { state ->
        NotificationRules.onSnapshot(previous, current, settings.current(), state, System.currentTimeMillis())
    }

    suspend fun onUnreachable() = update { state -> NotificationRules.onUnreachable(settings.current(), state, System.currentTimeMillis()) }

    suspend fun onBudget(budget: RateBudget) = update { state -> NotificationRules.onBudget(budget, state) }

    private suspend fun update(rule: suspend (NotifierState) -> Pair<List<Alert>, NotifierState>) = mutex.withLock {
        val (alerts, next) = rule(readState())
        writeState(next)
        if (settings.current().notificationsEnabled) alerts.forEach(::post)
    }

    private fun post(alert: Alert) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannels()
        val (channel, title, text) = when (alert) {
            is Alert.NewMessage -> Triple(
                if (alert.message.level == MessageLevel.Error) CHANNEL_ALERTS else CHANNEL_INFO,
                context.getString(R.string.notif_message_title, alert.message.code),
                context.getString(R.string.notif_message_text, alert.message.source.name, alert.message.timestampMillis?.let(::formatDateTime) ?: "–"),
            )
            Alert.Malfunction -> Triple(CHANNEL_ALERTS, context.getString(R.string.notif_malfunction_title), context.getString(R.string.notif_malfunction_text))
            is Alert.Pressure -> Triple(
                CHANNEL_ALERTS,
                context.getString(R.string.notif_pressure_title, formatNumber(alert.bar)),
                context.getString(R.string.notif_pressure_text, formatNumber(alert.min), formatNumber(alert.max)),
            )
            is Alert.GatewayOffline -> Triple(CHANNEL_ALERTS, context.getString(R.string.notif_offline_title), context.getString(R.string.notif_offline_text, formatDateTime(alert.sinceMillis)))
            is Alert.BudgetLow -> Triple(
                CHANNEL_INFO,
                context.getString(R.string.notif_budget_title),
                context.getString(R.string.notif_budget_text, alert.budget.remaining10m, alert.budget.remaining24h),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_nav_heating)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(alert.notificationId(), notification)
    }

    private fun Alert.notificationId(): Int = when (this) {
        is Alert.NewMessage -> message.key.hashCode()
        Alert.Malfunction -> 1
        is Alert.Pressure -> 2
        is Alert.GatewayOffline -> 3
        is Alert.BudgetLow -> 4
    }

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_INFO, context.getString(R.string.notif_channel_info), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private suspend fun readState(): NotifierState =
        context.notifierDataStore.data.first()[STATE]
            ?.let { runCatching { Json.decodeFromString(NotifierState.serializer(), it) }.getOrNull() }
            ?: NotifierState()

    private suspend fun writeState(state: NotifierState) {
        context.notifierDataStore.edit { it[STATE] = Json.encodeToString(NotifierState.serializer(), state) }
    }

    private companion object {
        const val CHANNEL_ALERTS = "boiler_alerts"
        const val CHANNEL_INFO = "boiler_info"
        val STATE = stringPreferencesKey("state")
    }
}
