package com.vigasboiler.app.analytics

/** `mock` flavor: sample data only, nothing to measure. */
object NoAnalytics : Analytics {
    override val isAvailable = false
    override fun setCollectionEnabled(enabled: Boolean) = Unit
    override fun screen(name: String) = Unit
    override fun event(name: String, params: Map<String, String>) = Unit
}
