package com.vigasboiler.app.analytics

import android.content.Context
import androidx.core.os.bundleOf
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.FirebaseAnalytics.ConsentStatus
import com.google.firebase.analytics.FirebaseAnalytics.ConsentType
import com.vigasboiler.app.BuildConfig

/**
 * Firebase / Google Analytics, only in release builds with a Firebase config. Collection starts
 * switched off (AndroidManifest of the live flavor) and follows the user's choice.
 */
class FirebaseAnalyticsTracker(context: Context) : Analytics {
    private val firebase: FirebaseAnalytics? =
        if (!BuildConfig.DEBUG && FirebaseApp.getApps(context).isNotEmpty()) FirebaseAnalytics.getInstance(context) else null

    override val isAvailable: Boolean get() = firebase != null

    override fun setCollectionEnabled(enabled: Boolean) {
        val analytics = firebase ?: return
        analytics.setConsent(
            mapOf(
                ConsentType.ANALYTICS_STORAGE to if (enabled) ConsentStatus.GRANTED else ConsentStatus.DENIED,
                ConsentType.AD_STORAGE to ConsentStatus.DENIED,
                ConsentType.AD_USER_DATA to ConsentStatus.DENIED,
                ConsentType.AD_PERSONALIZATION to ConsentStatus.DENIED,
            ),
        )
        analytics.setAnalyticsCollectionEnabled(enabled)
        // Withdrawing consent also removes what was collected on the phone and not yet sent.
        if (!enabled) analytics.resetAnalyticsData()
    }

    override fun screen(name: String) {
        firebase?.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundleOf(FirebaseAnalytics.Param.SCREEN_NAME to name, FirebaseAnalytics.Param.SCREEN_CLASS to name))
    }

    override fun event(name: String, params: Map<String, String>) {
        firebase?.logEvent(name, bundleOf(*params.map { (k, v) -> k to v.take(AnalyticsParams.MAX_VALUE) }.toTypedArray()))
    }
}
