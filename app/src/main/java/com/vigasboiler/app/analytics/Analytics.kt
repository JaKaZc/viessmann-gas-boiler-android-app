package com.vigasboiler.app.analytics

/**
 * Anonymous usage statistics. Nothing is sent until the user agreed ([setCollectionEnabled]),
 * and events carry only app-level facts: screen names, command names and outcomes. Never boiler
 * values, installation or gateway IDs, serial numbers, the client ID, account or location data.
 */
interface Analytics {
    /** False when nothing can be collected at all (mock flavor, debug build, no Firebase config). */
    val isAvailable: Boolean

    fun setCollectionEnabled(enabled: Boolean)

    fun screen(name: String)

    fun event(name: String, params: Map<String, String> = emptyMap())
}

/** Event names (Firebase: at most 40 characters, letters, digits and underscores). */
object AnalyticsEvents {
    /** Firebase's standard sign-in event; `method` is auth_tab or custom_tab. */
    const val LOGIN = "login"
    const val SIGN_IN_FAILED = "sign_in_failed"
    const val COMMAND = "command"
    const val REFRESH_LIMITED = "refresh_limited"
    const val THEME = "theme_changed"
    const val CSV_EXPORT = "csv_export"
    const val CLIENT_CHANGED = "client_changed"
}

object AnalyticsParams {
    const val METHOD = "method"
    const val REASON = "reason"
    const val FEATURE = "feature"
    const val COMMAND = "command"
    const val OUTCOME = "outcome"
    const val KIND = "kind"
    const val MODE = "mode"

    /** Firebase drops parameter values over 100 characters. */
    const val MAX_VALUE = 100
}

object AnalyticsNames {
    /** Route → screen name without its arguments (feature names, days): `feature:x` → `feature`. */
    fun screen(route: String): String = route.substringBefore(':')

    /**
     * Error category from an OAuth or HTTP message, e.g. `invalid_request: Invalid…` →
     * `invalid_request`. Only the code is kept so free text from servers never leaves the phone.
     */
    fun reason(message: String?): String =
        message?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 && it.none(Char::isWhitespace) } ?: "other"
}
