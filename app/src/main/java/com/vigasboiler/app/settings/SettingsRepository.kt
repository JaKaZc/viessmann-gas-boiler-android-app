package com.vigasboiler.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Light or dark colours: follow the phone, or fixed. */
enum class ThemeMode { System, Light, Dark }

data class AppSettings(
    val showDhw: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    /** Anonymous usage statistics: null until the user answered the question after sign-in. */
    val analyticsConsent: Boolean? = null,
    /** The "heating check is ready" pop-up was shown once (answered either way). */
    val heatingCheckOffered: Boolean = false,
    val backgroundIntervalMinutes: Int = 30,
    val retentionDays: Int = 365,
    /** Calorific value used to convert gas m³ to kWh (natural gas H ≈ 10.55). */
    val gasKwhPerCubicMeter: Double = 10.55,
    val notificationsEnabled: Boolean = true,
    val offlineAlertMinutes: Int = 60,
    val pressureMinBar: Double = 1.0,
    val pressureMaxBar: Double = 2.5,
) {
    companion object {
        val INTERVAL_RANGE = 15..120
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            showDhw = p[SHOW_DHW] ?: d.showDhw,
            themeMode = p[THEME_MODE]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: d.themeMode,
            analyticsConsent = p[ANALYTICS_CONSENT],
            heatingCheckOffered = p[CHECK_OFFERED] ?: d.heatingCheckOffered,
            backgroundIntervalMinutes = (p[INTERVAL] ?: d.backgroundIntervalMinutes).coerceIn(AppSettings.INTERVAL_RANGE),
            retentionDays = p[RETENTION] ?: d.retentionDays,
            gasKwhPerCubicMeter = p[GAS_FACTOR] ?: d.gasKwhPerCubicMeter,
            notificationsEnabled = p[NOTIFICATIONS] ?: d.notificationsEnabled,
            offlineAlertMinutes = p[OFFLINE_MINUTES] ?: d.offlineAlertMinutes,
            pressureMinBar = p[PRESSURE_MIN] ?: d.pressureMinBar,
            pressureMaxBar = p[PRESSURE_MAX] ?: d.pressureMaxBar,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        context.settingsDataStore.edit { p ->
            p[SHOW_DHW] = next.showDhw
            p[THEME_MODE] = next.themeMode.name
            next.analyticsConsent?.let { p[ANALYTICS_CONSENT] = it } ?: p.remove(ANALYTICS_CONSENT)
            p[CHECK_OFFERED] = next.heatingCheckOffered
            p[INTERVAL] = next.backgroundIntervalMinutes.coerceIn(AppSettings.INTERVAL_RANGE)
            p[RETENTION] = next.retentionDays.coerceAtLeast(1)
            p[GAS_FACTOR] = next.gasKwhPerCubicMeter
            p[NOTIFICATIONS] = next.notificationsEnabled
            p[OFFLINE_MINUTES] = next.offlineAlertMinutes.coerceAtLeast(5)
            p[PRESSURE_MIN] = next.pressureMinBar
            p[PRESSURE_MAX] = next.pressureMaxBar
        }
    }

    private companion object {
        val SHOW_DHW = booleanPreferencesKey("show_dhw")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ANALYTICS_CONSENT = booleanPreferencesKey("analytics_consent")
        val CHECK_OFFERED = booleanPreferencesKey("heating_check_offered")
        val INTERVAL = intPreferencesKey("background_interval_minutes")
        val RETENTION = intPreferencesKey("retention_days")
        val GAS_FACTOR = doublePreferencesKey("gas_kwh_per_m3")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val OFFLINE_MINUTES = intPreferencesKey("offline_alert_minutes")
        val PRESSURE_MIN = doublePreferencesKey("pressure_min_bar")
        val PRESSURE_MAX = doublePreferencesKey("pressure_max_bar")
    }
}
