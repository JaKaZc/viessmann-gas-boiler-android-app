package com.vigasboiler.app.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class RedactingLogInterceptorTest {

    @Test
    fun `ids serials and query values are masked`() {
        val url = "https://api.example.com/iot/v2/features/installations/1234567/gateways/7637415012345678/devices/0/features?filter=heating.circuits.0".toHttpUrl()
        assertEquals(
            "/iot/v2/features/installations/***/gateways/***/devices/0/features?filter=…",
            RedactingLogInterceptor.redactedPath(url),
        )
    }
}
