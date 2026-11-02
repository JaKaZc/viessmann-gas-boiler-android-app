package com.vigasboiler.app.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsNamesTest {

    @Test
    fun `screen names drop route arguments`() {
        assertEquals("dashboard", AnalyticsNames.screen("dashboard"))
        assertEquals("feature", AnalyticsNames.screen("feature:heating.circuits.0.heating.curve"))
        assertEquals("schedule", AnalyticsNames.screen("schedule:heating.circuits.0.heating.schedule|mon"))
    }

    @Test
    fun `only the error code of a sign-in failure is kept`() {
        assertEquals("invalid_request", AnalyticsNames.reason("invalid_request: Invalid redirection URI."))
        assertEquals("access_denied", AnalyticsNames.reason("access_denied"))
    }

    @Test
    fun `free text never becomes a reason`() {
        assertEquals("other", AnalyticsNames.reason("Token response contained no access token"))
        assertEquals("other", AnalyticsNames.reason(null))
        assertEquals("other", AnalyticsNames.reason(""))
        assertEquals("other", AnalyticsNames.reason("x".repeat(41) + ": too long"))
    }
}
